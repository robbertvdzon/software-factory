# syntax=docker/dockerfile:1.7

FROM maven:3.9.10-eclipse-temurin-21 AS build
WORKDIR /build
# dashboard-backend hangt sinds de bridge (docs/ontwerp-bridge-dashboard.md) af van
# factory-contracts (de bridge-frame-DTO's), dus hier bouwen we een mini-reactor
# (root-pom + factory-contracts + dashboard-backend) i.p.v. alleen de dashboard-backend-map.
# Zelfde patroon als Dockerfile.agent. `-pl dashboard-backend -am` houdt de overige
# modules (softwarefactory, agentworker) erbuiten.
COPY pom.xml .
COPY docker/prepare-mini-reactor.sh /usr/local/bin/prepare-mini-reactor
COPY factory-contracts/pom.xml factory-contracts/pom.xml
COPY dashboard-backend/pom.xml dashboard-backend/pom.xml
RUN prepare-mini-reactor pom.xml dashboard-backend \
    && mvn -q -pl dashboard-backend -am dependency:go-offline
COPY factory-contracts/src factory-contracts/src
COPY dashboard-backend/src dashboard-backend/src
RUN mvn -q -DskipTests -pl dashboard-backend -am package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/dashboard-backend/target/softwarefactory-dashboard-backend-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
