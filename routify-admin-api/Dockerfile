# ─── Build stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY routify-common/pom.xml routify-common/
COPY routify-admin-api/pom.xml routify-admin-api/
RUN ./mvnw dependency:go-offline -pl routify-admin-api -am -q
COPY routify-common/src/ routify-common/src/
COPY routify-admin-api/src/ routify-admin-api/src/
RUN ./mvnw package -pl routify-admin-api -am -DskipTests -q

# ─── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S routify && adduser -S routify -G routify
USER routify
WORKDIR /app
COPY --from=build /build/routify-admin-api/target/*.jar app.jar
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseZGC -XX:+ZGenerational --enable-preview -Dspring.threads.virtual.enabled=true"
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8082/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

