# syntax=docker/dockerfile:1.7
# Builds one AstraWMS service from the monorepo: docker build --build-arg MODULE=inventory-service -f deploy/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY platform platform
COPY services services
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl services/${MODULE} -am -DskipTests package

FROM eclipse-temurin:21-jre
ARG MODULE
RUN useradd --system --no-create-home astra
COPY --from=build /src/services/${MODULE}/target/${MODULE}-*.jar /app/app.jar
USER astra
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
