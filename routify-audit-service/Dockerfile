FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
RUN apk add --no-cache maven
COPY pom.xml .
COPY routify-common routify-common
COPY routify-audit-service routify-audit-service
RUN mvn package -DskipTests -pl routify-audit-service -am --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S routify && adduser -S routify -G routify
USER routify
COPY --from=build /app/routify-audit-service/target/*.jar app.jar
ENTRYPOINT ["java", "-XX:+UseVirtualThreads", "-jar", "app.jar"]

