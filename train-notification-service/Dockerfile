# 1. Build Stage (On compile dans le conteneur pour ne pas dépendre de ta version de Maven)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
# Agent OTel en premier pour profiter du cache Docker (indépendant du code source)
# curl --fail : échoue proprement si le download rate (ADD silencieux sur redirect multi-hop)
RUN curl -L --fail -o /app/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 2. Run Stage (L'image finale)
# ⚠️ NE PAS utiliser eclipse-temurin:21-jre-alpine ici !
# google-cloud-pubsub embarque des libs natives gRPC/Netty compilées pour glibc (Debian/Ubuntu).
# Alpine utilise musl libc → SIGSEGV au démarrage (netty_tcnative SSLContext crash).
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Patch les CVE OS (Ubuntu 22.04 jammy) sans changer de base image
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
COPY --from=build /app/target/train-notification-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
# -javaagent toujours actif. Pour désactiver sans reconstruire : OTEL_TRACES_EXPORTER=none
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]
