# syntax=docker/dockerfile:1

# ==============================================================================
# Stage 1: Builder
# Compiles the source code and packages the production executable JAR.
# ==============================================================================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy project descriptor and source tree
COPY pom.xml .
COPY src ./src

# Compile and package application JAR (tests are verified in dedicated CI matrix)
RUN mvn clean package -B -DskipTests

# ==============================================================================
# Stage 2: Runtime
# Ultra-lightweight and secure runtime container (~150MB).
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
    JAVA_OPTS="-Xms64m -Xmx512m"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar ultimo-server.jar"]
