# The panel image is deliberately boring: one runtime, no build step, no shell wrappers.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev --no-audit --no-fund

FROM node:22-alpine
ENV NODE_ENV=production
WORKDIR /app
RUN addgroup -S ptunnel && adduser -S -G ptunnel ptunnel
COPY --from=deps /app/node_modules ./node_modules
COPY package.json ./
COPY src ./src
USER ptunnel
EXPOSE 8080
# Liveness only - readiness needs the core and is checked by the deploy gate.
HEALTHCHECK --interval=20s --timeout=3s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PT_PORT||8080)+'/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "src/server.js"]
