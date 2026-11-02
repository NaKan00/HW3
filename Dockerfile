# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY flight-contract flight-contract
COPY flight-service flight-service
COPY booking-service booking-service
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -DskipTests package
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
ARG SERVICE
WORKDIR /app
COPY --from=build /build/${SERVICE}/target/${SERVICE}-1.0.0.jar /app/app.jar
USER 10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
