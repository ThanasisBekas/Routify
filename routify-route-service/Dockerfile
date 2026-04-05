# ─── Build stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

# Copy Maven wrapper and POMs for dependency caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY routify-common/pom.xml routify-common/
COPY routify-route-service/pom.xml routify-route-service/

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -pl routify-route-service -am -q

# Copy source
COPY routify-common/src/ routify-common/src/
COPY routify-route-service/src/ routify-route-service/src/

# Build
RUN ./mvnw package -pl routify-route-service -am -DskipTests -q

# ─── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS runtime

# Non-root user for security
RUN addgroup -S routify && adduser -S routify -G routify
USER routify

WORKDIR /app

COPY --from=build /build/routify-route-service/target/*.jar app.jar

# JVM tuning for containers + enable virtual threads
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseZGC \
  -Dspring.threads.virtual.enabled=true \
"

EXPOSE 8081 9081

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:9081/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

