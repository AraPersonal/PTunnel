import { config } from './config.js';

/**
 * Write-Forwarding Middleware for Multi-Node Cluster (ADR-0009 / Design 0003).
 *
 * In PTunnel's single-writer replica architecture:
 * - Read requests (GET /v1/locations, GET /v1/discovery/presets, user profiles, etc.)
 *   and local authentication (POST /v1/auth/login) execute 100% locally from the replicated SQLite database.
 * - Mutating requests (POST discovery observations, preset reports, profile updates, traffic sync, admin actions)
 *   received by a follower node are transparently forwarded to the Leader (Germany 1).
 * - This guarantees zero lost writes and immediate consistency without split-brain issues.
 */

// Routes that should always execute locally even if they use POST
const LOCAL_POST_ROUTES = new Set([
  '/v1/auth/login',
  '/v1/login',
  '/auth/login',
  '/v1/auth/logout',
  '/admin/api/auth/login',
]);

export function shouldForward(req) {
  if (config.clusterRole !== 'follower') return false;

  const method = req.method.toUpperCase();
  if (['GET', 'HEAD', 'OPTIONS'].includes(method)) return false;

  const path = req.path || req.url.split('?')[0];

  // Local node-internal endpoints must remain local
  if (path.startsWith('/internal/')) return false;

  // Local login checks remain local
  if (LOCAL_POST_ROUTES.has(path)) return false;

  return true;
}

export function writeForwardingMiddleware() {
  return async (req, res, next) => {
    if (!shouldForward(req)) {
      return next();
    }

    const leaderBase = config.clusterLeaderUrl || 'https://86-54-42-124.nip.io';
    try {
      const fullPath = req.originalUrl || req.url;
      const targetUrl = new URL(fullPath, leaderBase).toString();

      console.log(`[write-forwarding] Forwarding ${req.method} ${fullPath} -> Leader (${targetUrl})`);

      const headers = new Headers();
      for (const [key, val] of Object.entries(req.headers)) {
        const k = key.toLowerCase();
        if (k === 'host' || k === 'content-length' || k === 'connection') continue;
        headers.set(key, Array.isArray(val) ? val.join(', ') : String(val));
      }

      // Propagate client IP and cluster auth secret
      const clientIp = req.headers['x-forwarded-for'] || req.ip || req.socket.remoteAddress || '';
      headers.set('x-forwarded-for', String(clientIp));
      headers.set('x-cluster-secret', config.internalSecret || '');
      headers.set('x-forwarded-from-node', config.boxId || config.host || '');

      const init = {
        method: req.method,
        headers,
      };

      if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(req.method)) {
        if (typeof req.body === 'object' && req.body !== null) {
          init.body = JSON.stringify(req.body);
          headers.set('content-type', 'application/json');
        } else if (typeof req.body === 'string') {
          init.body = req.body;
        }
      }

      const response = await fetch(targetUrl, init);

      res.status(response.status);
      response.headers.forEach((value, header) => {
        const h = header.toLowerCase();
        if (!['transfer-encoding', 'content-encoding', 'content-length'].includes(h)) {
          res.setHeader(header, value);
        }
      });

      const buffer = await response.arrayBuffer();
      res.send(Buffer.from(buffer));
    } catch (err) {
      console.error(`[write-forwarding] Failed to forward ${req.method} ${req.url} to Leader:`, err.message);
      res.status(502).json({
        error: 'Cluster leader is temporarily unreachable for state mutation. Read operations and tunnels remain operational.',
        code: 'leader_unreachable',
        details: err.message,
      });
    }
  };
}
