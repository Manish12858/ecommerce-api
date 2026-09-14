# syntax=docker/dockerfile:1.7
#
# ecommerce-api — production image
#
#   docker build -t ecommerce-api .
#   docker run -p 8080:8080 \
#     -e DB_URL=jdbc:mysql://db-host:3306/ecommerce_db \
#     -e DB_USER=ecommerce -e DB_PASSWORD=... \
#     ecommerce-api
#
# Three stages:
#   build   — Temurin 21 JDK, Maven wrapper, dependency layer cached separately from sources
#   extract — splits the fat jar into Spring Boot layers so a code change doesn't re-push ~40 MB of deps
#   runtime — Temurin 21 JRE (Alpine), non-root, health-checked
#
# Tests are NOT run here: they need a live MySQL (ddl-auto=validate + contextLoads).
# Run `./mvnw verify` in CI before building the image.

ARG JAVA_VERSION=21

# --- 1. build ------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is reused until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -B dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -B package -DskipTests \
 && mv target/*.jar target/application.jar

# --- 2. extract layers ----------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS extract
WORKDIR /extract
COPY --from=build /workspace/target/application.jar .
RUN java -Djarmode=tools -jar application.jar extract --layers --destination layers

# --- 3. runtime -----------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime

# Unprivileged user; no shell, no home, fixed uid so volume ownership is predictable.
RUN addgroup -S -g 10001 app \
 && adduser  -S -u 10001 -G app -H -s /sbin/nologin app

WORKDIR /app

# Least-changing layers first — dependencies rarely change, application code changes every build.
COPY --from=extract --chown=app:app /extract/layers/dependencies/          ./
COPY --from=extract --chown=app:app /extract/layers/spring-boot-loader/    ./
COPY --from=extract --chown=app:app /extract/layers/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /extract/layers/application/           ./

USER app:app

# Container-aware heap sizing; die (and get restarted) rather than limp on after OOM.
# JAVA_TOOL_OPTIONS is read by the JVM automatically and can be overridden at `docker run`.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8080

EXPOSE 8080

# start-period covers JVM + Hibernate schema validation; the endpoint is public by default
# (management.endpoints.web.exposure.include is unset -> health only).
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${SERVER_PORT}/actuator/health" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "application.jar"]
