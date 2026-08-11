# syntax=docker/dockerfile:1

# One Dockerfile, one build, three images. Each service selects its own final stage via
# `target:` in docker-compose.yml, so the Maven build below runs once and is reused from the
# layer cache for all three - rather than three images each compiling the whole repository.

# --- build ------------------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copied whole rather than poms-first: the frontend-maven-plugin downloads its own Node toolchain
# during the build, so a dependency-only pre-fetch layer would not cover the expensive half anyway.
COPY pom.xml ./
COPY game-contracts game-contracts
COPY game-engine-service game-engine-service
COPY game-session-service game-session-service
COPY game-ui-service game-ui-service

RUN mvn -B -DskipTests package

# --- shared runtime ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre AS runtime
# curl is here for the compose healthchecks; without it `depends_on: service_healthy` has no way
# to ask a service whether it is ready.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home /app app
WORKDIR /app
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]

# --- service images ---------------------------------------------------------------------------

FROM runtime AS game-engine-service
COPY --from=build /workspace/game-engine-service/target/game-engine-service-*.jar /app/application.jar
EXPOSE 8081

FROM runtime AS game-session-service
COPY --from=build /workspace/game-session-service/target/game-session-service-*.jar /app/application.jar
EXPOSE 8082

FROM runtime AS game-ui-service
COPY --from=build /workspace/game-ui-service/target/game-ui-service-*.jar /app/application.jar
EXPOSE 8080
