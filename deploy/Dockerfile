# syntax=docker/dockerfile:1.7
ARG MODULE

# ---------------------------------------------------------------------------
# Build stage. Maven is downloaded rather than taken from a maven:* image,
# because those images track their own JDK and we need exactly Java 26.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:26-jdk AS build

ARG MAVEN_VERSION=3.9.9
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    | tar -xz -C /opt \
 && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn \
 && apt-get purge -y curl && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /src

# Poms first, so a source-only change does not re-resolve every dependency.
COPY pom.xml ./
COPY contracts/pom.xml contracts/
COPY platform-security/pom.xml platform-security/
COPY user-service/pom.xml user-service/
COPY auth-service/pom.xml auth-service/
COPY performance-service/pom.xml performance-service/
COPY notification-service/pom.xml notification-service/
COPY edge-gateway/pom.xml edge-gateway/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline -DskipTests || true

COPY contracts contracts
COPY platform-security platform-security
COPY user-service user-service
COPY auth-service auth-service
COPY performance-service performance-service
COPY notification-service notification-service
COPY edge-gateway edge-gateway

ARG MODULE
# -am builds the modules this one depends on; -pl limits the rest.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl ${MODULE} -am -DskipTests package

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:26-jre AS runtime
ARG MODULE

# Never root: a container escape should not begin with uid 0.
RUN useradd --system --uid 10001 --create-home drenix
USER 10001:10001
WORKDIR /app

# Boot's repackage goal leaves the plain jar as *.jar.original, so this picks the fat jar.
COPY --from=build --chown=10001:10001 /src/${MODULE}/target/${MODULE}-*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
