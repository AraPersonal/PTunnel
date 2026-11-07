# The core, pinned and checksum-verified at build time. Nothing is downloaded when the container starts,
# and the version that serves traffic is the version written in this file.
ARG XRAY_VERSION=26.10.29
FROM alpine:3.20 AS fetch
ARG XRAY_VERSION
RUN apk add --no-cache curl unzip ca-certificates
WORKDIR /tmp
RUN set -eux; \
    base="https://github.com/XTLS/Xray-core/releases/download/v${XRAY_VERSION}"; \
    curl -fsSLo xray.zip "${base}/Xray-linux-64.zip"; \
    curl -fsSLo xray.dgst "${base}/Xray-linux-64.zip.dgst"; \
    expected="$(sed -n 's/^SHA2-256= *//p' xray.dgst | head -1)"; \
    actual="$(sha256sum xray.zip | cut -d' ' -f1)"; \
    [ -n "$expected" ] && [ "$expected" = "$actual" ] || { echo "checksum mismatch: $expected vs $actual"; exit 1; }; \
    unzip -q xray.zip xray -d /out

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata && adduser -S -H -s /sbin/nologin xray
COPY --from=fetch /out/xray /usr/local/bin/xray
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh
USER xray
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
