# Digest-pinned (Scorecard PinnedDependencies); Dependabot's docker ecosystem keeps it current.
FROM eclipse-temurin:25-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151

ARG VERSION
ARG GIT_SHORT_HASH
ARG DATE="1970-01-01T00:00:00Z"

RUN apk add --no-cache tcpdump

COPY target/riptide-flows-*.jar /app/riptide.jar

ENTRYPOINT [ "java" ]

CMD [ "-jar", "/app/riptide.jar" ]


LABEL org.opencontainers.image.created="${DATE}" \
      org.opencontainers.image.authors="https://github.com/Riptide-Labs/riptide/blob/main/CODEOWNERS" \
      org.opencontainers.image.url="ghcr.io/riptide-labs/riptide" \
      org.opencontainers.image.source="https://github.com/Riptide-Labs/riptide" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${GIT_SHORT_HASH}" \
      org.opencontainers.image.vendor="RiptideLabs" \
      org.opencontainers.image.licenses="GPL-3.0-or-later"

## Runtime information to listen for Flows on UDP port 9999 by default

EXPOSE 9999/udp
