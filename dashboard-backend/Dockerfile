# syntax=docker/dockerfile:1.7

FROM maven:3.9.10-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./pom.xml
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/softwarefactory-dashboard-backend-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
