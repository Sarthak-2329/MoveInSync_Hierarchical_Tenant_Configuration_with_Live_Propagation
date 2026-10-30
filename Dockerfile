# ==========================================
# Multi-Stage Dockerfile for Config Server
# ==========================================

# Stage 1: Build & Package with Maven
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /workspace

# Cache dependency layer
COPY pom.xml ./
COPY config-core/pom.xml config-core/
COPY config-client/pom.xml config-client/
COPY config-server/pom.xml config-server/
RUN mvn dependency:go-offline -B || true

# Copy full source and build executable fat jar
COPY config-core/src config-core/src
COPY config-client/src config-client/src
COPY config-server/src config-server/src
RUN mvn clean package -DskipTests -B

# Stage 2: Production JRE Runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Create non-root user and persistent data directory
RUN useradd -u 1001 appuser && \
    mkdir -p /app/data && \
    chown -R appuser:appuser /app

# Copy executable jar from builder stage
COPY --from=builder --chown=appuser:appuser /workspace/config-server/target/config-server-*.jar /app/app.jar

USER appuser

EXPOSE 8080
VOLUME /app/data

# JVM flags optimized for containerized workloads
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
