# syntax=docker/dockerfile:1

# ==============================================================================
# Stage 1: Builder
# Compiles the source code, runs all unit/integration tests, and packages the JAR.
# ==============================================================================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache Maven dependencies by copying pom.xml first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy project source files
COPY src ./src

# Compile and package application (verifying all tests pass)
RUN mvn clean package -B

# ==============================================================================
# Stage 2: Runtime
# Ultra-lightweight and secure runtime container (~150MB).
# Runs as non-root user with Generational ZGC optimized for virtual threads.
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="Stanislaw" \
      description="Authoritative multiplayer Ultimo card game server" \
      version="1.0.0"

# Security best practice: run as non-privileged service user
RUN addgroup -S ultimo && adduser -S ultimo -G ultimo

WORKDIR /app

# Copy the compiled JAR artifact from the builder stage
COPY --from=builder --chown=ultimo:ultimo /build/target/ultimo-server.jar ./ultimo-server.jar

# Drop root privileges
USER ultimo

# Default TCP port for game sessions
EXPOSE 8080

# Environment variables with sensible production defaults
ENV PORT=8080 \
    JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xms64m -Xmx256m"

# Periodic TCP socket healthcheck to ensure server acceptor is healthy
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD nc -z 127.0.0.1 ${PORT} || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar ultimo-server.jar"]
