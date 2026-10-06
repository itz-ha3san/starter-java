FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/starter-service.jar ./app.jar
ENV SERVER_ADDRESS=0.0.0.0
USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
