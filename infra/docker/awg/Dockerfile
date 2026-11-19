# Multi-stage build for AmneziaWG (amneziawg-go and amneziawg-tools)
# Pinned version for reproducible, hermetic builds.
ARG GOLANG_VERSION=1.23-alpine
FROM golang:${GOLANG_VERSION} AS builder
RUN apk add --no-cache git make build-base linux-headers

# Build amneziawg-go (userspace implementation)
ARG AWG_GO_VERSION=v0.2.14
WORKDIR /src
RUN git clone --depth 1 --branch ${AWG_GO_VERSION} https://github.com/amnezia-vpn/amneziawg-go.git \
    && cd amneziawg-go \
    && make \
    && cp amneziawg-go /usr/local/bin/

# Build amneziawg-tools (awg and awg-quick)
ARG AWG_TOOLS_VERSION=v1.0.20240213
RUN git clone --depth 1 --branch ${AWG_TOOLS_VERSION} https://github.com/amnezia-vpn/amneziawg-tools.git \
    && cd amneziawg-tools/src \
    && make \
    && cp wg /usr/local/bin/awg \
    && cp wg-quick/linux.bash /usr/local/bin/awg-quick

FROM alpine:3.20
RUN apk add --no-cache iptables iproute2 bash ca-certificates
COPY --from=builder /usr/local/bin/amneziawg-go /usr/local/bin/amneziawg-go
COPY --from=builder /usr/local/bin/awg /usr/local/bin/awg
COPY --from=builder /usr/local/bin/awg-quick /usr/local/bin/awg-quick
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh /usr/local/bin/awg-quick

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
