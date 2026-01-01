FROM eclipse-temurin:25-alpine

ARG VERSION
ARG GIT_SHORT_HASH
ARG DATE="1970-01-01T00:00:00Z"
ARG REGISTRY_REPOSITORY="riptide-dev"

RUN apk add --no-cache tcpdump

COPY target/riptide-flows-*.jar /app/riptide.jar

ENTRYPOINT [ "java" ]

CMD [ "-jar", "/app/riptide.jar" ]


LABEL org.opencontainers.image.created="${DATE}" \
      org.opencontainers.image.authors="https://github.com/Riptide-Labs/riptide/blob/main/CODEOWNERS" \
      org.opencontainers.image.url="ghcr.io/riptide-labs/${REGISTRY_REPOSITORY}" \
      org.opencontainers.image.source="https://github.com/Riptide-Labs/riptide" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${GIT_SHORT_HASH}" \
      org.opencontainers.image.vendor="RiptideLabs" \
      org.opencontainers.image.licenses=" qGPL-3.0"

## Runtime information to listen for Flows on UDP port 9999 by default

EXPOSE 9999/udp
