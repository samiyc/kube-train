# 1. Build Stage (On compile dans le conteneur pour ne pas dépendre de ta version de Maven)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 2. Run Stage (L'image finale, légère)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/kube-train-api-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
