FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
# نام jar با finalName در pom.xml هماهنگ است (s3-payment-service)
COPY --from=build /src/target/s3-payment-service.jar ./app.jar
ENV SERVER_ADDRESS=0.0.0.0
USER 10001:10001
# پورت پیش‌فرض S3 طبق GEN-15
EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
