# One image serving the Anthropic Messages passthrough below each /compat/<adapter> mount. The build and runtime
# images are ai-dial-core's, so the JVM is the one the provider module is embedded into there.
FROM gradle:9.9-jdk25-alpine AS builder

COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle --no-daemon :app:installDist

# A JDK, not a JRE: a profiler records JFR with jcmd inside the container.
FROM eclipse-temurin:25-jdk-alpine

RUN apk update && apk upgrade --no-cache libcrypto3 libssl3 libexpat binutils gpgv gnutls libpng zlib brotli-libs musl musl-utils p11-kit p11-kit-trust

WORKDIR /app
RUN adduser -u 1001 --disabled-password --gecos "" appuser
COPY --from=builder --chown=appuser:appuser /home/gradle/src/app/build/install/model-provider/ .

USER appuser
EXPOSE 5000

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=6 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:5000/health || exit 1

ENV LOG_LEVEL=INFO

# The base image's entrypoint (/__cacert_entrypoint.sh) imports /certificates/*.crt when USE_SYSTEM_CA_CERTS is set,
# then runs the command.
CMD ["/app/bin/model-provider"]
