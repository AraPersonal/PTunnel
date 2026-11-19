import crypto from 'node:crypto';

// Execution of docs/design/0001: an open operator registry that clients grow themselves,
// plus crowd validation of config shapes. Identity is data; operator -> parameters is never hardcoded.

const SECRET_KEYS = new Set([
  'uuid', 'password', 'passwd', 'secret', 'token', 'privatekey', 'private_key',
  'auth', 'username', 'email', 'user', 'users',
]);
const PROBE_METHODS = new Set(['range-512k', 'range-1m']);
const CONTROL_CHARS = /[\u0000-\u001f\u007f-\u009f\u200e\u200f\u202a-\u202e\u2066-\u2069]/g;
const SETTING_KEYS = [
  'K', 'N', 'T_kBps', 'D', 'R_days', 'K_op', 'T_stable_min',
  'observePerUidPerDay', 'observeGlobalPerDay', 'reportPerUidPerHour', 'maxCandidates',
];

export function sanitizeLabel(value) {
  return String(value ?? '').replace(CONTROL_CHARS, ' ').replace(/\s+/g, ' ').trim().slice(0, 64);
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    const out = {};
    for (const key of Object.keys(value).sort()) {
      if (SECRET_KEYS.has(key.toLowerCase())) continue;
      out[key] = canonical(value[key]);
    }
    return out;
  }
  return value;
}

export function configIdFor(params) {
  return crypto.createHash('sha256').update(JSON.stringify(canonical(params ?? {}))).digest('hex').slice(0, 16);
}

const num = (value) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
};

export function cleanEvidence(raw = {}) {
  const mcc = num(raw.mcc);
  const mnc = num(raw.mnc);
  const asn = num(raw.asn);
  return {
    mcc: mcc !== null && mcc > 0 ? Math.trunc(mcc) : null,
    mnc: mnc !== null && mnc >= 0 ? Math.trunc(mnc) : null,
    asn: asn !== null && asn > 0 ? Math.trunc(asn) : null,
    isp_org: sanitizeLabel(raw.isp_org),
    suggested_label: sanitizeLabel(raw.sim_operator_name ?? raw.suggested_label),
  };
}

// A later report that knows less must not erase what an earlier one proved.
function compactEvidence(evidence = {}) {
  const out = {};
  for (const [key, value] of Object.entries(evidence)) {
    if (value === null || value === undefined || value === '') continue;
    out[key] = value;
  }
  return out;
}

export function deriveOperatorKey(evidence) {
  if (evidence.mcc && evidence.mnc !== null) return `m.${evidence.mcc}.${evidence.mnc}`;
  if (evidence.asn) return `a.${evidence.asn}`;
  return null;
}

export function deriveNetworkKey({ mcc, mnc, asn, prefix24, province }) {
  const region = sanitizeLabel(province).toLowerCase().replace(/[^a-z0-9-]/g, '').slice(0, 12) || 'unk';
  if (mcc && mnc !== null && mnc !== undefined) return `c.${mcc}.${mnc}.${region}`;
  const prefix = String(prefix24 || '').split('.').slice(0, 3).join('.');
  if (asn || prefix) return `f.${asn || 'unk'}.${prefix || 'unk'}`;
  return null;
}

function median(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

// One loud client must not decide for everybody: drop samples further than 3 scaled MADs from the median.
function rejectOutliers(samples) {
  const throughputs = samples.map((s) => s.throughput_kBps).filter((v) => v !== null);
  const med = median(throughputs);
  if (med === null) return samples;
  const mad = median(throughputs.map((v) => Math.abs(v - med)));
  if (!mad) return samples;
  const limit = 3 * 1.4826 * mad;
  return samples.filter((s) => s.throughput_kBps === null || Math.abs(s.throughput_kBps - med) <= limit);
}

export function createDiscovery({ db, config, ipIntel, now = () => new Date(), serverEvidence = null }) {
  const iso = () => now().toISOString();
  const cutoff = (days) => new Date(now().getTime() - days * 86400000).toISOString();

  function settings() {
    const stored = {};
    for (const row of db.prepare('SELECT key, value FROM preset_settings').all()) {
      const n = Number(row.value);
      if (Number.isFinite(n)) stored[row.key] = n;
    }
    return { ...config.discovery, ...stored };
  }

  function setSettings(patch, actor) {
    const applied = {};
    const positive = new Set(['K', 'N', 'T_kBps', 'K_op']);
    for (const key of SETTING_KEYS) {
      const n = num(patch?.[key]);
      if (n === null || n < 0 || (positive.has(key) && n === 0)) continue;
      if (key === 'D' && n > 1) continue;
      db.prepare('INSERT INTO preset_settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value')
        .run(key, String(n));
      applied[key] = n;
    }
    audit(actor, 'settings', 'preset_settings', applied);
    return settings();
  }

  function audit(actor, action, target, detail) {
    db.prepare('INSERT INTO admin_audit (actor, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?)')
      .run(String(actor || 'system'), action, String(target || ''), JSON.stringify(detail ?? {}), iso());
  }

  const rowToOperator = (row) => row && ({
    operator_key: row.operator_key,
    operator_tag: row.operator_tag,
    display_name: row.display_name,
    network_type: row.network_type,
    status: row.status,
    evidence: JSON.parse(row.evidence),
    aliases: JSON.parse(row.aliases),
    merged_into: row.merged_into,
    mismatch: !!row.mismatch,
    source: row.source,
    clients: db.prepare('SELECT COUNT(*) AS n FROM operator_observations WHERE operator_key = ?').get(row.operator_key).n,
    first_seen: row.first_seen,
    last_seen: row.last_seen,
  });

  const getOperator = (key) => db.prepare('SELECT * FROM operators WHERE operator_key = ?').get(key);

  function resolveKey(key) {
    let current = key;
    for (let i = 0; i < 5; i += 1) {
      const row = getOperator(current);
      if (!row || row.status !== 'merged' || !row.merged_into) return current;
      current = row.merged_into;
    }
    return current;
  }

  function upsertOperator({ key, tag, displayName, networkType, evidence, source, mismatch = false, status = 'provisional' }) {
    const existing = getOperator(key);
    const timestamp = iso();
    if (!existing) {
      db.prepare(`
        INSERT INTO operators (operator_key, operator_tag, display_name, network_type, evidence, status, aliases, mismatch, source, first_seen, last_seen)
        VALUES (?, ?, ?, ?, ?, ?, '[]', ?, ?, ?, ?)
      `).run(key, tag || `auto:${key}`, displayName || '', networkType || 'unknown', JSON.stringify(compactEvidence(evidence)),
        status, mismatch ? 1 : 0, source, timestamp, timestamp);
      return getOperator(key);
    }
    // An admin-set identity is never overwritten by a later client claim.
    const locked = existing.source === 'admin';
    const mergedEvidence = { ...JSON.parse(existing.evidence), ...compactEvidence(evidence) };
    const knownType = networkType && networkType !== 'unknown' ? networkType : existing.network_type;
    db.prepare(`
      UPDATE operators SET last_seen = ?, evidence = ?, network_type = ?, mismatch = ?,
        display_name = ?, operator_tag = ?, status = ?
      WHERE operator_key = ?
    `).run(
      timestamp,
      JSON.stringify(mergedEvidence),
      locked ? existing.network_type : knownType,
      existing.mismatch || mismatch ? 1 : 0,
      locked ? existing.display_name : (existing.display_name || displayName || ''),
      locked ? existing.operator_tag : (existing.operator_tag || tag || `auto:${key}`),
      locked ? existing.status : existing.status,
      key,
    );
    return getOperator(key);
  }

  function noteObservation(key, uid, networkKey, source) {
    db.prepare(`
      INSERT INTO operator_observations (operator_key, uid, network_key, source, created_at) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(operator_key, uid) DO UPDATE SET network_key = excluded.network_key, created_at = excluded.created_at
    `).run(key, uid, networkKey || '', source, iso());
    // Server-side discovery is logged separately: polling /v1/presets must not eat a user's observe quota.
    db.prepare('INSERT INTO discovery_log (uid, kind, key, created_at) VALUES (?, ?, ?, ?)')
      .run(uid, source === 'client' ? 'observe' : 'server', key, iso());
    const clients = db.prepare('SELECT COUNT(*) AS n FROM operator_observations WHERE operator_key = ?').get(key).n;
    const row = getOperator(key);
    if (row.status === 'provisional' && !row.mismatch && clients >= settings().K_op) {
      db.prepare("UPDATE operators SET status = 'confirmed' WHERE operator_key = ?").run(key);
    }
    return clients;
  }

  function overQuota(kind, uid, limit, windowMs) {
    const since = new Date(now().getTime() - windowMs).toISOString();
    const mine = db.prepare('SELECT COUNT(*) AS n FROM discovery_log WHERE uid = ? AND kind = ? AND created_at > ?').get(uid, kind, since).n;
    if (mine >= limit) return true;
    if (kind !== 'observe') return false;
    const all = db.prepare('SELECT COUNT(*) AS n FROM discovery_log WHERE kind = ? AND created_at > ?').get(kind, since).n;
    return all >= settings().observeGlobalPerDay;
  }

  function serverView(ip) {
    const view = ipIntel.view(ip);
    if (!view.usable) return null;
    return { asn: view.asn, org: view.org, country: view.country, region: view.region, prefix24: view.prefix24 };
  }

  // Server-side discovery: works even for clients that never call observe, but only on a positively
  // identified Iranian line — a CDN edge or our own exit must never become an "operator".
  function noteServerLine(uid, ip) {
    const view = ipIntel.view(ip);
    if (!view.usable || !view.asn || view.country !== 'IR') return null;
    const evidence = { asn: view.asn, isp_org: view.org };
    const key = `a.${view.asn}`;
    const networkKey = deriveNetworkKey({ asn: view.asn, prefix24: view.prefix24, province: view.region });
    upsertOperator({
      key, tag: `auto:${key}`, displayName: sanitizeLabel(view.org), networkType: 'unknown',
      evidence, source: 'server',
    });
    noteObservation(key, uid, networkKey, 'server');
    return { operator_key: key, network_key: networkKey };
  }

  function observe({ uid, body, ip }) {
    const view = ipIntel.view(ip);
    if (view.own) return { error: 'own_box_ip', message: 'Observation must come from the physical line, not a PTunnel exit' };
    if (overQuota('observe', uid, settings().observePerUidPerDay, 86400000)) {
      return { error: 'rate_limited', message: 'Too many observations for today' };
    }

    const evidence = cleanEvidence(body?.evidence);
    const claimedKey = sanitizeLabel(body?.operator_key).toLowerCase();
    const key = deriveOperatorKey(evidence) || (/^(m|a)\.[0-9.]+$/.test(claimedKey) ? claimedKey : null);
    if (!key) return { error: 'no_evidence', message: 'Need MCC/MNC or ASN to register a network' };

    const server = serverView(ip);
    const networkType = evidence.mcc && evidence.mnc !== null ? 'cellular' : (evidence.asn || server?.asn ? 'fixed' : 'unknown');
    let mismatch = false;
    if (server) {
      if (evidence.asn && server.asn && evidence.asn !== server.asn) mismatch = true;
      if (server.country && server.country !== 'IR') mismatch = true;
    }

    const networkKey = deriveNetworkKey({
      mcc: evidence.mcc,
      mnc: evidence.mnc,
      asn: server?.asn ?? evidence.asn,
      prefix24: server?.prefix24 || sanitizeLabel(body?.client_view?.prefix24),
      province: server?.region || body?.client_view?.province_guess,
    });

    const row = upsertOperator({
      key,
      // A client-supplied tag is never canonical: the key is numeric, the name comes from seed/admin.
      tag: `auto:${key}`,
      displayName: evidence.suggested_label || evidence.isp_org || sanitizeLabel(server?.org),
      networkType,
      evidence: { ...evidence, asn: evidence.asn ?? server?.asn ?? null, isp_org: evidence.isp_org || sanitizeLabel(server?.org) },
      source: 'client',
      mismatch,
    });
    const clients = noteObservation(key, uid, networkKey, 'client');
    const fresh = getOperator(key);
    const confidence = fresh.status === 'confirmed' && !fresh.mismatch ? 'high' : (server && !mismatch ? 'medium' : 'low');
    const seeds = db.prepare("SELECT COUNT(*) AS n FROM preset_configs WHERE is_seed = 1 AND is_alive = 1 AND network_type IN ('any', ?)").get(networkType).n;

    return {
      operator_key: key,
      operator_tag: fresh.operator_tag,
      display_name: fresh.display_name,
      status: fresh.status,
      confidence,
      clients,
      network_key: networkKey,
      server_view: server,
      mismatch: !!fresh.mismatch,
      presets_hint: { network_type_seeds: seeds },
      operator: rowToOperator(fresh),
    };
  }

  function upsertConfig({ engine, transport, params, networkType = 'any', isSeed = false }) {
    const clean = canonical(params ?? {});
    const id = configIdFor(clean);
    const timestamp = iso();
    db.prepare(`
      INSERT INTO preset_configs (config_id, engine, transport, params, network_type, is_seed, is_alive, created_at, last_seen)
      VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
      ON CONFLICT(config_id) DO UPDATE SET last_seen = excluded.last_seen
    `).run(id, String(engine || 'xray'), String(transport || ''), JSON.stringify(clean), networkType, isSeed ? 1 : 0, timestamp, timestamp);
    return id;
  }

  function aggregate(operatorKey, networkKey, configId) {
    const rows = db.prepare(`
      SELECT uid, latency_ms, throughput_kBps, verdict, deaths FROM preset_samples
      WHERE operator_key = ? AND network_key = ? AND config_id = ? AND created_at > ?
    `).all(operatorKey, networkKey, configId, cutoff(settings().R_days));
    // Outlier rejection only guards throughput *claims*. A reported death is never discarded:
    // silencing it would let one fast client hide a door that is dying for everybody else.
    const kept = [...rejectOutliers(rows.filter((s) => s.verdict === 'ok')), ...rows.filter((s) => s.verdict !== 'ok')];
    const deaths = kept.reduce((sum, s) => sum + (s.deaths || 0), 0);
    const dead = kept.filter((s) => s.verdict !== 'ok').length + deaths;
    const rounds = kept.length + deaths;
    return {
      samples: kept.length,
      clients: new Set(kept.map((s) => s.uid)).size,
      p50_latency_ms: median(kept.map((s) => s.latency_ms).filter((v) => v !== null)),
      p50_throughput_kBps: median(kept.map((s) => s.throughput_kBps).filter((v) => v !== null)),
      death_rate: rounds ? Math.min(1, dead / rounds) : 0,
    };
  }

  function refreshValidation(operatorKey, networkKey, configId, { corroborated = false } = {}) {
    const t = settings();
    const stats = aggregate(operatorKey, networkKey, configId);
    const existing = db.prepare('SELECT * FROM preset_validations WHERE operator_key = ? AND network_key = ? AND config_id = ?')
      .get(operatorKey, networkKey, configId);
    const timestamp = iso();
    const confidence = Math.max(0, Math.min(1,
      0.4 * Math.min(1, stats.clients / t.K) +
      0.2 * Math.min(1, stats.samples / t.N) +
      0.3 * Math.min(1, (stats.p50_throughput_kBps || 0) / t.T_kBps) +
      0.1 * (1 - stats.death_rate)));

    const contradicts = stats.death_rate > 0.5 || (stats.p50_throughput_kBps !== null && stats.p50_throughput_kBps < t.T_kBps / 5);
    const qualifies = stats.samples >= t.N && stats.clients >= t.K &&
      (stats.p50_throughput_kBps || 0) >= t.T_kBps && stats.death_rate <= t.D;

    let status = 'candidate';
    const source = existing?.source || 'crowd';
    // An admin decision stands until an admin changes it; one loud client must not undo it.
    const adminLocked = existing?.source === 'admin' && existing.status !== 'candidate';
    if (adminLocked) status = existing.status;
    else if (contradicts) status = 'retired';
    else if (qualifies && corroborated) status = 'validated';

    db.prepare(`
      INSERT INTO preset_validations (operator_key, network_key, config_id, status, samples, clients,
        p50_latency_ms, p50_throughput_kBps, death_rate, confidence, server_corroborated, source, first_seen, last_seen)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(operator_key, network_key, config_id) DO UPDATE SET
        status = excluded.status, samples = excluded.samples, clients = excluded.clients,
        p50_latency_ms = excluded.p50_latency_ms, p50_throughput_kBps = excluded.p50_throughput_kBps,
        death_rate = excluded.death_rate, confidence = excluded.confidence,
        server_corroborated = excluded.server_corroborated, last_seen = excluded.last_seen
    `).run(operatorKey, networkKey, configId, status, stats.samples, stats.clients, stats.p50_latency_ms,
      stats.p50_throughput_kBps, stats.death_rate, confidence, corroborated ? 1 : 0, source,
      existing?.first_seen || timestamp, timestamp);

    return { status, ...stats, confidence, server_corroborated: corroborated };
  }

  // Freshness decay & housekeeping routine (design 0001 §2.1 rule 6):
  // 1. Freshness decay: a validated row nobody re-measured within R days drops back to candidate.
  // 2. Archive unobserved provisional operator rows after R days (source != 'admin').
  function applyHousekeeping() {
    const threshold = cutoff(settings().R_days);
    const decayed = db.prepare("UPDATE preset_validations SET status = 'candidate' WHERE status = 'validated' AND source = 'crowd' AND last_seen < ?")
      .run(threshold);
    const archived = db.prepare("UPDATE operators SET status = 'archived' WHERE status = 'provisional' AND source != 'admin' AND last_seen < ?")
      .run(threshold);
    return {
      decayed_validations: decayed.changes,
      archived_operators: archived.changes,
      cutoff: threshold,
    };
  }

  function applyDecay() {
    return applyHousekeeping();
  }

  function report({ uid, body }) {
    if (overQuota('report', uid, settings().reportPerUidPerHour, 3600000)) {
      return { error: 'rate_limited', message: 'Too many reports in the last hour' };
    }
    const method = String(body?.probe_method || '');
    if (!PROBE_METHODS.has(method)) {
      return { error: 'invalid_probe_method', message: 'Only range-chunked throughput probes count (range-512k / range-1m)' };
    }
    const rawSamples = Array.isArray(body?.samples) ? body.samples.slice(0, 50) : [];
    if (!rawSamples.length) return { error: 'no_samples', message: 'At least one measured sample is required' };

    const stability = body?.stability ?? {};
    const connectedMinutes = num(stability.connected_minutes) ?? 0;
    if (connectedMinutes < settings().T_stable_min) {
      return { error: 'not_stable', message: `Report only after ${settings().T_stable_min} minutes of stable connection` };
    }

    const evidence = cleanEvidence(body?.evidence);
    const server = serverView(body?.ip);
    const operatorKey = resolveKey(sanitizeLabel(body?.operator_key).toLowerCase() || deriveOperatorKey(evidence) || '');
    const networkKey = sanitizeLabel(body?.network_key).toLowerCase() || deriveNetworkKey({
      mcc: evidence.mcc, mnc: evidence.mnc, asn: server?.asn ?? evidence.asn,
      prefix24: server?.prefix24, province: server?.region,
    });
    if (!operatorKey || !networkKey) return { error: 'no_network', message: 'operator_key and network_key are required' };

    let configId = sanitizeLabel(body?.config_id).toLowerCase();
    const known = configId && db.prepare('SELECT config_id FROM preset_configs WHERE config_id = ?').get(configId);
    if (!known) {
      if (!body?.params || !body?.transport) return { error: 'unknown_config', message: 'Unknown config_id; send params + transport to register the shape' };
      configId = upsertConfig({ engine: body.engine, transport: body.transport, params: body.params });
    }

    const deaths = Math.max(0, Math.trunc(num(stability.deaths) ?? 0));
    const timestamp = iso();
    let accepted = 0;
    for (const sample of rawSamples) {
      const throughput = num(sample.throughput_kBps);
      const verdict = String(sample.verdict || '').toLowerCase();
      if (throughput === null || throughput < 0 || !['ok', 'dead', 'fail'].includes(verdict)) continue;
      db.prepare(`
        INSERT INTO preset_samples (operator_key, network_key, config_id, uid, latency_ms, throughput_kBps,
          bytes, duration_s, verdict, connected_minutes, deaths, probe_method, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).run(operatorKey, networkKey, configId, uid, num(sample.latency_ms), throughput,
        Math.max(0, Math.trunc(num(sample.bytes) ?? 0)), num(sample.duration_s) ?? 0, verdict,
        // Deaths belong to the report, not to each of its samples.
        connectedMinutes, accepted === 0 ? deaths : 0, method, timestamp);
      accepted += 1;
    }
    if (!accepted) return { error: 'no_valid_samples', message: 'Samples need numeric throughput_kBps and a verdict' };

    db.prepare('INSERT INTO discovery_log (uid, kind, key, created_at) VALUES (?, ?, ?, ?)').run(uid, 'report', configId, timestamp);

    upsertOperator({
      key: operatorKey, networkType: evidence.mcc ? 'cellular' : 'unknown',
      evidence, source: 'client',
    });
    noteObservation(operatorKey, uid, networkKey, 'client');

    // Independent server-side byte telemetry lands in phase 5; until then a crowd row stays candidate.
    const corroborated = serverEvidence ? !!serverEvidence({ operatorKey, networkKey, configId, uid }) : false;
    const validation = refreshValidation(operatorKey, networkKey, configId, { corroborated });
    return { ok: true, operator_key: operatorKey, network_key: networkKey, config_id: configId, accepted, validation };
  }

  function presets({ uid, query, ip }) {
    applyHousekeeping();
    const server = serverView(ip);
    const evidence = cleanEvidence({ mcc: query.mcc, mnc: query.mnc, asn: query.asn ?? server?.asn });
    let operatorKey = sanitizeLabel(query.operator_key).toLowerCase();
    if (!operatorKey && query.operator_tag) {
      const byTag = db.prepare('SELECT operator_key FROM operators WHERE operator_tag = ?').get(String(query.operator_tag));
      operatorKey = byTag?.operator_key || '';
    }
    if (!operatorKey) operatorKey = deriveOperatorKey(evidence) || '';
    operatorKey = resolveKey(operatorKey);

    const networkKey = sanitizeLabel(query.network_key).toLowerCase() || deriveNetworkKey({
      mcc: evidence.mcc, mnc: evidence.mnc, asn: evidence.asn,
      prefix24: server?.prefix24, province: server?.region,
    }) || '';
    const networkType = String(query.network_type || (evidence.mcc ? 'cellular' : 'unknown'));

    let operator = operatorKey ? getOperator(operatorKey) : null;
    if (operatorKey && !operator) {
      upsertOperator({
        key: operatorKey, networkType, evidence, source: 'server',
        displayName: sanitizeLabel(server?.org),
      });
      noteObservation(operatorKey, uid, networkKey, 'server');
      operator = getOperator(operatorKey);
    }

    const rows = operatorKey ? db.prepare(`
      SELECT v.*, c.engine, c.transport, c.params, c.is_seed FROM preset_validations v
      JOIN preset_configs c ON c.config_id = v.config_id
      WHERE c.is_alive = 1 AND v.status != 'retired' AND (v.network_key = ? OR v.operator_key = ?)
    `).all(networkKey, operatorKey) : [];

    const rank = (row) => {
      const exact = row.network_key === networkKey ? 0 : 1;
      return (row.status === 'validated' ? 0 : 2) + exact;
    };
    rows.sort((a, b) => rank(a) - rank(b) || b.confidence - a.confidence || (b.p50_throughput_kBps || 0) - (a.p50_throughput_kBps || 0));

    // A door retired on this network must not sneak back in through the seed list.
    const retired = new Set(operatorKey ? db.prepare(`
      SELECT config_id FROM preset_validations
      WHERE status = 'retired' AND (network_key = ? OR operator_key = ?)
    `).all(networkKey, operatorKey).map((r) => r.config_id) : []);

    const candidates = [];
    const seen = new Set();
    const push = (row) => {
      if (retired.has(row.config_id) || seen.has(row.config_id) || candidates.length >= settings().maxCandidates) return;
      seen.add(row.config_id);
      candidates.push({
        priority: candidates.length + 1,
        config_id: row.config_id,
        status: row.status,
        confidence: row.confidence ?? 0,
        clients: row.clients ?? 0,
        p50_throughput_kBps: row.p50_throughput_kBps ?? null,
        engine: row.engine,
        transport: row.transport,
        params: JSON.parse(row.params),
      });
    };
    for (const row of rows) push(row);
    const seeds = db.prepare("SELECT * FROM preset_configs WHERE is_seed = 1 AND is_alive = 1 AND network_type IN ('any', ?)").all(networkType);
    for (const seed of seeds) push({ ...seed, status: 'seed', confidence: 0, clients: 0, p50_throughput_kBps: null });

    return {
      operator_key: operatorKey || null,
      operator_tag: operator?.operator_tag ?? null,
      operator_status: operator?.status ?? 'unknown',
      network_key: networkKey || null,
      network_type: networkType,
      generated_at: iso(),
      candidates,
    };
  }

  function operators() {
    applyHousekeeping();
    const rows = db.prepare("SELECT * FROM operators WHERE status NOT IN ('merged', 'archived') ORDER BY last_seen DESC LIMIT 500").all();
    const suffixes = ['.ir', ...db.prepare("SELECT value FROM domestic_rules WHERE kind = 'suffix' ORDER BY value").all().map((r) => r.value)];
    return {
      operators: rows.map(rowToOperator),
      domestic: { suffixes: [...new Set(suffixes)], geoip: 'IR' },
    };
  }

  function adminOperator({ actor, body }) {
    const evidence = cleanEvidence(body?.evidence);
    const key = sanitizeLabel(body?.operator_key).toLowerCase() || deriveOperatorKey(evidence);
    if (!key) return { error: 'no_evidence', message: 'operator_key or evidence required' };
    const tag = sanitizeLabel(body?.operator_tag) || `auto:${key}`;
    const existing = getOperator(key);
    if (existing) {
      db.prepare("UPDATE operators SET operator_tag = ?, display_name = ?, network_type = ?, evidence = ?, status = 'confirmed', source = 'admin', last_seen = ? WHERE operator_key = ?")
        .run(tag, sanitizeLabel(body?.display_name) || existing.display_name, String(body?.network_type || existing.network_type),
          JSON.stringify({ ...JSON.parse(existing.evidence), ...evidence }), iso(), key);
    } else {
      upsertOperator({
        key, tag, displayName: sanitizeLabel(body?.display_name), networkType: String(body?.network_type || 'unknown'),
        evidence, source: 'admin', status: 'confirmed',
      });
    }
    audit(actor, 'operator_upsert', key, { tag });
    return { ok: true, operator: rowToOperator(getOperator(key)) };
  }

  function confirmOperator({ actor, key }) {
    const row = getOperator(resolveKey(key));
    if (!row) return { error: 'not_found', message: 'Unknown operator_key' };
    db.prepare("UPDATE operators SET status = 'confirmed', source = 'admin', last_seen = ? WHERE operator_key = ?").run(iso(), row.operator_key);
    audit(actor, 'operator_confirm', row.operator_key, {});
    return { ok: true, operator: rowToOperator(getOperator(row.operator_key)) };
  }

  function renameOperator({ actor, key, displayName, operatorTag }) {
    const row = getOperator(resolveKey(key));
    if (!row) return { error: 'not_found', message: 'Unknown operator_key' };
    db.prepare('UPDATE operators SET display_name = ?, operator_tag = ?, last_seen = ? WHERE operator_key = ?')
      .run(sanitizeLabel(displayName) || row.display_name, sanitizeLabel(operatorTag) || row.operator_tag, iso(), row.operator_key);
    audit(actor, 'operator_rename', row.operator_key, { from: row.operator_tag, to: operatorTag || row.operator_tag });
    return { ok: true, operator: rowToOperator(getOperator(row.operator_key)) };
  }

  function mergeOperators({ actor, fromKey, intoKey }) {
    const from = getOperator(fromKey);
    const into = getOperator(resolveKey(intoKey));
    if (!from || !into) return { error: 'not_found', message: 'Both operator keys must exist' };
    if (from.operator_key === into.operator_key) return { error: 'invalid_request', message: 'Cannot merge a key into itself' };

    db.exec('BEGIN');
    try {
      const validations = db.prepare('SELECT network_key, config_id FROM preset_validations WHERE operator_key = ?').all(from.operator_key);
      for (const v of validations) {
        const clash = db.prepare('SELECT 1 FROM preset_validations WHERE operator_key = ? AND network_key = ? AND config_id = ?')
          .get(into.operator_key, v.network_key, v.config_id);
        if (clash) {
          db.prepare('DELETE FROM preset_validations WHERE operator_key = ? AND network_key = ? AND config_id = ?')
            .run(from.operator_key, v.network_key, v.config_id);
        } else {
          db.prepare('UPDATE preset_validations SET operator_key = ? WHERE operator_key = ? AND network_key = ? AND config_id = ?')
            .run(into.operator_key, from.operator_key, v.network_key, v.config_id);
        }
      }
      const observations = db.prepare('SELECT uid FROM operator_observations WHERE operator_key = ?').all(from.operator_key);
      for (const o of observations) {
        db.prepare('DELETE FROM operator_observations WHERE operator_key = ? AND uid = ?').run(from.operator_key, o.uid);
        db.prepare(`
          INSERT INTO operator_observations (operator_key, uid, network_key, source, created_at)
          VALUES (?, ?, '', 'admin', ?) ON CONFLICT(operator_key, uid) DO NOTHING
        `).run(into.operator_key, o.uid, iso());
      }
      db.prepare('UPDATE preset_samples SET operator_key = ? WHERE operator_key = ?').run(into.operator_key, from.operator_key);
      const aliases = [...new Set([...JSON.parse(into.aliases), from.operator_key, ...JSON.parse(from.aliases)])];
      db.prepare('UPDATE operators SET aliases = ?, last_seen = ? WHERE operator_key = ?').run(JSON.stringify(aliases), iso(), into.operator_key);
      db.prepare("UPDATE operators SET status = 'merged', merged_into = ?, last_seen = ? WHERE operator_key = ?").run(into.operator_key, iso(), from.operator_key);
      db.exec('COMMIT');
    } catch (err) {
      db.exec('ROLLBACK');
      throw err;
    }
    audit(actor, 'operator_merge', from.operator_key, { into: into.operator_key });
    return { ok: true, operator: rowToOperator(getOperator(into.operator_key)) };
  }

  function adminValidation({ actor, body, status }) {
    const operatorKey = resolveKey(sanitizeLabel(body?.operator_key).toLowerCase());
    const networkKey = sanitizeLabel(body?.network_key).toLowerCase();
    if (!operatorKey || !networkKey) return { error: 'invalid_request', message: 'operator_key and network_key required' };
    let configId = sanitizeLabel(body?.config_id).toLowerCase();
    if (!db.prepare('SELECT config_id FROM preset_configs WHERE config_id = ?').get(configId)) {
      if (!body?.params || !body?.transport) return { error: 'unknown_config', message: 'Unknown config_id; send params + transport' };
      configId = upsertConfig({ engine: body.engine, transport: body.transport, params: body.params });
    }
    const timestamp = iso();
    db.prepare(`
      INSERT INTO preset_validations (operator_key, network_key, config_id, status, samples, clients,
        p50_latency_ms, p50_throughput_kBps, death_rate, confidence, server_corroborated, source, first_seen, last_seen)
      VALUES (?, ?, ?, ?, 0, 0, NULL, NULL, 0, 1, 0, 'admin', ?, ?)
      ON CONFLICT(operator_key, network_key, config_id) DO UPDATE SET
        status = excluded.status, source = 'admin', confidence = 1, last_seen = excluded.last_seen
    `).run(operatorKey, networkKey, configId, status, timestamp, timestamp);
    audit(actor, `preset_${status}`, `${operatorKey}/${networkKey}/${configId}`, {});
    return { ok: true, operator_key: operatorKey, network_key: networkKey, config_id: configId, status };
  }

  function addDomestic({ actor, suffixes }) {
    const added = [];
    for (const raw of Array.isArray(suffixes) ? suffixes : []) {
      const value = sanitizeLabel(raw).toLowerCase();
      if (!value.startsWith('.') || value.length < 3) continue;
      db.prepare("INSERT INTO domestic_rules (kind, value, source, created_at) VALUES ('suffix', ?, 'admin', ?) ON CONFLICT(kind, value) DO NOTHING")
        .run(value, iso());
      added.push(value);
    }
    audit(actor, 'domestic_add', 'domestic_rules', { added });
    return { ok: true, added, domestic: operators().domestic };
  }

  // Seeds are our own measured-alive doors, explicitly unvalidated. Dead inbounds (ss-2022 users[]) never enter.
  function seedDoors() {
    upsertConfig({ engine: 'xray', transport: 'reality', params: { port: config.realityPort, sni: config.realitySni, fingerprint: 'chrome' }, isSeed: true });
    upsertConfig({ engine: 'xray', transport: 'kcp', params: { port: config.kcpPort }, isSeed: true });
  }

  // Known cellular identities only. MVNOs share a host MNC and fixed-line ASNs are not guessable,
  // so both are left to real observations instead of a hardcoded table.
  function seedOperators() {
    const known = [
      { key: 'm.432.11', tag: 'mci', name: 'همراه اول', mcc: 432, mnc: 11 },
      { key: 'm.432.35', tag: 'irancell', name: 'ایرانسل', mcc: 432, mnc: 35 },
      { key: 'm.432.20', tag: 'rightel', name: 'رایتل', mcc: 432, mnc: 20 },
    ];
    for (const op of known) {
      if (getOperator(op.key)) continue;
      upsertOperator({
        key: op.key, tag: op.tag, displayName: op.name, networkType: 'cellular',
        evidence: { mcc: op.mcc, mnc: op.mnc }, source: 'admin', status: 'confirmed',
      });
    }
  }

  return {
    settings, setSettings, audit, observe, noteServerLine, report, presets, operators,
    adminOperator, confirmOperator, renameOperator, mergeOperators, adminValidation, addDomestic,
    seedDoors, seedOperators, upsertConfig, resolveKey, applyDecay, applyHousekeeping,
    allPresets: () => ({
      configs: db.prepare('SELECT * FROM preset_configs ORDER BY created_at DESC LIMIT 100').all(),
      validations: db.prepare('SELECT * FROM preset_validations ORDER BY last_seen DESC LIMIT 200').all(),
    }),
    auditLog: (limit = 50) => db.prepare('SELECT * FROM admin_audit ORDER BY id DESC LIMIT ?').all(Math.trunc(limit)),
  };
}

export function registerDiscoveryRoutes(app, { discovery, auth, adminAuth }) {
  const fail = (res, result, code = 400) => res.status(code).json({ error: result.error, message: result.message });

  app.get('/v1/operators', auth, (req, res) => {
    const payload = discovery.operators();
    const body = JSON.stringify(payload);
    const etag = `W/"${crypto.createHash('sha256').update(body).digest('hex').slice(0, 16)}"`;
    res.setHeader('ETag', etag);
    if (req.headers['if-none-match'] === etag) return res.status(304).end();
    res.type('application/json').send(body);
  });

  app.post('/v1/operators/observe', auth, (req, res) => {
    const result = discovery.observe({ uid: req.user.uid, body: req.body, ip: req.ip });
    if (result.error) return fail(res, result, result.error === 'rate_limited' ? 429 : 400);
    res.json(result);
  });

  app.get('/v1/presets', auth, (req, res) => {
    res.json(discovery.presets({ uid: req.user.uid, query: req.query, ip: req.ip }));
  });

  app.post('/v1/presets/report', auth, (req, res) => {
    const result = discovery.report({ uid: req.user.uid, body: { ...req.body, ip: req.ip } });
    if (result.error) return fail(res, result, result.error === 'rate_limited' ? 429 : 400);
    res.json(result);
  });

  app.post('/v1/admin/operators', adminAuth, (req, res) => {
    const result = discovery.adminOperator({ actor: req.user.username, body: req.body });
    if (result.error) return fail(res, result);
    res.json(result);
  });

  app.post('/v1/admin/operators/confirm', adminAuth, (req, res) => {
    const result = discovery.confirmOperator({ actor: req.user.username, key: String(req.body?.operator_key || '') });
    if (result.error) return fail(res, result, 404);
    res.json(result);
  });

  app.post('/v1/admin/operators/rename', adminAuth, (req, res) => {
    const result = discovery.renameOperator({
      actor: req.user.username, key: String(req.body?.operator_key || ''),
      displayName: req.body?.display_name, operatorTag: req.body?.operator_tag,
    });
    if (result.error) return fail(res, result, 404);
    res.json(result);
  });

  app.post('/v1/admin/operators/merge', adminAuth, (req, res) => {
    const result = discovery.mergeOperators({
      actor: req.user.username, fromKey: String(req.body?.from_key || ''), intoKey: String(req.body?.into_key || ''),
    });
    if (result.error) return fail(res, result, result.error === 'not_found' ? 404 : 400);
    res.json(result);
  });

  app.post('/v1/admin/presets/validate', adminAuth, (req, res) => {
    const result = discovery.adminValidation({ actor: req.user.username, body: req.body, status: 'validated' });
    if (result.error) return fail(res, result);
    res.json(result);
  });

  app.post('/v1/admin/presets/retire', adminAuth, (req, res) => {
    const result = discovery.adminValidation({ actor: req.user.username, body: req.body, status: 'retired' });
    if (result.error) return fail(res, result);
    res.json(result);
  });

  app.post('/v1/admin/presets/seed', adminAuth, (req, res) => {
    if (!req.body?.params || !req.body?.transport) {
      return res.status(400).json({ error: 'invalid_request', message: 'transport and params required' });
    }
    const configId = discovery.upsertConfig({
      engine: req.body.engine, transport: req.body.transport, params: req.body.params,
      networkType: String(req.body.network_type || 'any'), isSeed: true,
    });
    discovery.audit(req.user.username, 'preset_seed', configId, { transport: req.body.transport });
    res.json({ ok: true, config_id: configId, status: 'seed' });
  });

  app.post('/v1/admin/domestic', adminAuth, (req, res) => {
    res.json(discovery.addDomestic({ actor: req.user.username, suffixes: req.body?.suffixes }));
  });

  app.post('/v1/admin/settings', adminAuth, (req, res) => {
    res.json({ ok: true, settings: discovery.setSettings(req.body, req.user.username) });
  });

  app.get('/v1/admin/audit', adminAuth, (req, res) => {
    res.json({ audit: discovery.auditLog(Number(req.query.limit) || 50) });
  });

  app.get('/v1/admin/presets/all', adminAuth, (_req, res) => {
    res.json(discovery.allPresets());
  });

  app.post('/v1/admin/housekeeping', adminAuth, (req, res) => {
    const result = discovery.applyHousekeeping();
    discovery.audit(req.user.username, 'housekeeping_run', 'all', result);
    res.json({ ok: true, ...result });
  });
}