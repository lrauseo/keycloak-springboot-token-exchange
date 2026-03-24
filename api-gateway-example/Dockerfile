# ============================================================================
# Multi-stage build for api-gateway-example
# Uses Gradle Docker image — no local Gradle installation needed
# ============================================================================

# Stage 1: Build
FROM gradle:8.11-jdk21 AS builder
WORKDIR /app

# Copy build files first for better layer caching
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Download dependencies (cached layer — only re-runs when build files change)
RUN gradle dependencies --no-daemon --quiet 2>&1 | tail -3 || true

# Copy source code
COPY src ./src

# Build the fat JAR (skipping tests for faster build)
RUN gradle bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Add non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
