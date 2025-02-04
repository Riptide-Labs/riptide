FROM eclipse-temurin:23-alpine

ARG VERSION
ARG GIT_SHORT_HASH
ARG DATE="1970-01-01T00:00:00Z"
ARG REGISTRY_REPOSITORY="riptide-dev"

RUN apk add --no-cache tcpdump

COPY target/riptide-flows-*.jar /app/riptide.jar

ENTRYPOINT [ "java" ]

CMD [ "-jar", "/app/riptide.jar" ]


LABEL org.opencontainers.image.created="${DATE}" \
      org.opencontainers.image.authors="https://github.com/PikkaLabs/riptide/blob/main/CODEOWNERS" \
      org.opencontainers.image.url="https://quay.io/repository/pikkalabs/${REGISTRY_REPOSITORY}" \
      org.opencontainers.image.source="https://github.com/PikkaLabs/riptide" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${GIT_SHORT_HASH}" \
      org.opencontainers.image.vendor="Riptide" \
      org.opencontainers.image.licenses="Apache-2.0"

## Runtime information to listen for Flows on UDP port 9999 by default

EXPOSE 9999/udp
