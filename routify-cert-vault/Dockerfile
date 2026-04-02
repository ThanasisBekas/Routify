FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
RUN apk add --no-cache maven
COPY pom.xml .
COPY routify-common routify-common
COPY routify-cert-vault routify-cert-vault
RUN mvn package -DskipTests -pl routify-cert-vault -am --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S routify && adduser -S routify -G routify
USER routify
COPY --from=build /app/routify-cert-vault/target/*.jar app.jar
ENTRYPOINT ["java", "-XX:+UseVirtualThreads", "-jar", "app.jar"]

