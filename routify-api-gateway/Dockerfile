# ─── Build stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY routify-common/pom.xml routify-common/
COPY routify-api-gateway/pom.xml routify-api-gateway/
RUN ./mvnw dependency:go-offline -pl routify-api-gateway -am -q
COPY routify-common/src/ routify-common/src/
COPY routify-api-gateway/src/ routify-api-gateway/src/
RUN ./mvnw package -pl routify-api-gateway -am -DskipTests -q

# ─── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S routify && adduser -S routify -G routify
USER routify
WORKDIR /app
COPY --from=build /build/routify-api-gateway/target/*.jar app.jar
# Gateway uses WebFlux (Netty) — no virtual threads needed
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseZGC -XX:+ZGenerational --enable-preview"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
