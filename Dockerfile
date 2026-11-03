# Digest-pinned (Scorecard PinnedDependencies); Dependabot's docker ecosystem keeps it current.
FROM eclipse-temurin:25-alpine@sha256:425f0b733cb23843bd78dbe5552513976d0cf12b9722752c48a5161cf5cd2f32

ARG VERSION
ARG GIT_SHORT_HASH
ARG DATE="1970-01-01T00:00:00Z"

RUN apk add --no-cache tcpdump

COPY target/riptide-flows-*.jar /app/riptide.jar

# Default JVM flags (#924). JAVA_TOOL_OPTIONS is read before the command line and before
# JDK_JAVA_OPTIONS, so an operator flag in JDK_JAVA_OPTIONS wins (-XX:-UseCompactObjectHeaders
# turns this off) and overriding CMD keeps it. Setting JAVA_TOOL_OPTIONS replaces it.
ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders"

ENTRYPOINT [ "java" ]

CMD [ "-jar", "/app/riptide.jar" ]


LABEL org.opencontainers.image.created="${DATE}" \
      org.opencontainers.image.authors="https://github.com/Riptide-Labs/riptide/blob/main/CODEOWNERS" \
      org.opencontainers.image.url="ghcr.io/riptide-labs/riptide" \
      org.opencontainers.image.source="https://github.com/Riptide-Labs/riptide" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${GIT_SHORT_HASH}" \
      org.opencontainers.image.vendor="Riptide Labs" \
      org.opencontainers.image.licenses="GPL-3.0-or-later"

## Runtime information to listen for Flows on UDP port 9999 by default

EXPOSE 9999/udp
