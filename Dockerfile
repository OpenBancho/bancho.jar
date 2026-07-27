# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle ./

RUN chmod +x gradlew

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies || true

COPY src/ src/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew jar copyDeps

FROM eclipse-temurin:25-jre

WORKDIR /app

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        netcat-openbsd \
        libicu78 \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p logs .config data

COPY --from=build /app/build/libs/server.jar ./server.jar
COPY --from=build /app/build/libs/lib ./lib

ENV JAVA_OPTS="--enable-native-access=ALL-UNNAMED"
ENV JAVA_XMS=512m
ENV JAVA_XMX=2g

ENTRYPOINT ["sh", "-c", "exec java -Xms${JAVA_XMS} -Xmx${JAVA_XMX} ${JAVA_OPTS} -cp server.jar:lib/* com.osuserverlist.bjar.App \"$@\"", "--"]