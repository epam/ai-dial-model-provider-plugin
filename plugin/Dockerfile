FROM --platform=$BUILDPLATFORM gradle:9.9-jdk25-alpine AS builder

COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN --mount=type=secret,id=GPR_USERNAME,env=GPR_USERNAME --mount=type=secret,id=GPR_PASSWORD,env=GPR_PASSWORD \
  gradle --no-daemon :plugin:pluginJar

FROM scratch
COPY --from=builder --chmod=644 /home/gradle/src/plugin/build/plugins/ /
