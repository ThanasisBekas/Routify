FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
RUN apk add --no-cache maven
COPY pom.xml .
COPY routify-common routify-common
COPY routify-cert-vault routify-cert-vault
RUN mvn package -DskipTests -pl routify-cert-vault -am --no-transfer-progress

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S routify && adduser -S routify -G routify
USER routify
WORKDIR /app
COPY --from=build /app/routify-cert-vault/target/*.jar app.jar
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseZGC -Dspring.threads.virtual.enabled=true"
EXPOSE 8085 9085
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:9085/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

