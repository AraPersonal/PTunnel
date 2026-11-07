# Hysteria2 server, pinned by version AND digest. The digest is taken from the release asset metadata,
# not from a second-hand file, so a replaced upload fails the build instead of being trusted.
ARG HYSTERIA_VERSION=app/v2.13.0
ARG HYSTERIA_SHA256=907ba8c9693edb104b20582681fb7dc15639d5b64a9cbb616a7b539190a86691
FROM alpine:3.20 AS fetch
ARG HYSTERIA_VERSION
ARG HYSTERIA_SHA256
RUN apk add --no-cache curl ca-certificates
RUN set -eux; \
    mkdir -p /out; \
    url="https://github.com/HyNetworks/hysteria/releases/download/${HYSTERIA_VERSION}/hysteria-linux-amd64"; \
    curl -fsSLo /out/hysteria "$url"; \
    echo "${HYSTERIA_SHA256}  /out/hysteria" | sha256sum -c -; \
    chmod 0755 /out/hysteria

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata
COPY --from=fetch /out/hysteria /usr/local/bin/hysteria
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh
# The container only runs one static binary; it gets no capabilities and a read-only rootfs from compose.
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
