# syntax=docker/dockerfile:1.7
# ---- build ----------------------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY fx-core/pom.xml fx-core/
COPY fx-app/pom.xml fx-app/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl fx-core dependency:go-offline || true
COPY fx-core/src fx-core/src
COPY fx-app/src fx-app/src
# Unit tests run in CI (`mvn verify`); the image build skips them to stay fast and Docker-in-Docker free.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && cp fx-app/target/fx-app-*.jar /app.jar

# ---- runtime --------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
