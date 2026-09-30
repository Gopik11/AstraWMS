# syntax=docker/dockerfile:1.7
# Builds one AstraWMS module from the monorepo:
#   docker build --build-arg MODULE=inventory-service -f deploy/Dockerfile .
#   docker build --build-arg MODULE=sap-adapter --build-arg MODULE_DIR=adapters -f deploy/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY platform platform
COPY services services
COPY adapters adapters
ARG MODULE
ARG MODULE_DIR=services
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${MODULE_DIR}/${MODULE} -am -DskipTests package

FROM eclipse-temurin:21-jre
ARG MODULE
ARG MODULE_DIR=services
RUN useradd --system --no-create-home astra
COPY --from=build /src/${MODULE_DIR}/${MODULE}/target/${MODULE}-*.jar /app/app.jar
USER astra
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
