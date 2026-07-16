# 1. Build Stage (On compile dans le conteneur pour ne pas dépendre de ta version de Maven)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
# Agent OTel en premier pour profiter du cache Docker (indépendant du code source)
# curl --fail : échoue proprement si le download rate (ADD silencieux sur redirect multi-hop)
RUN curl -L --fail -o /app/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar
# Agent Cloud Profiler (lab Phase 0 F5) — même philosophie que l'agent OTel :
# embarqué au build, mais ACTIVÉ seulement par -agentpath dans JAVA_TOOL_OPTIONS
# (deployment-gke.yaml). Retirer la variable d'env le désactive sans rebuild.
RUN curl -L --fail -o /tmp/profiler_java_agent.tar.gz \
      https://storage.googleapis.com/cloud-profiler/java/latest/profiler_java_agent.tar.gz \
 && mkdir -p /opt/cprof \
 && tar -xzf /tmp/profiler_java_agent.tar.gz -C /opt/cprof \
 && rm /tmp/profiler_java_agent.tar.gz
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
# Équivalent d'un `apt-get upgrade` sur le serveur → corrige openssl, curl, etc.
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
# Binaire natif de l'agent Profiler (lab Phase 0 F5). Lu seul (read-only FS OK).
COPY --from=build /opt/cprof /opt/cprof
COPY --from=build /app/target/kube-train-api-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
# -javaagent toujours actif. Pour désactiver sans reconstruire : OTEL_TRACES_EXPORTER=none
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]
