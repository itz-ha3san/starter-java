FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# Use Aliyun mirror for Maven Central.
# Reason: from some networks (notably Iran), repo.maven.apache.org fails with
# "Remote host terminated the handshake" / "SSL peer shut down incorrectly".
# Aliyun mirrors Maven Central 1:1 and is reachable from those networks.
# See .mvn-settings.xml for the mirror definition.
COPY .mvn-settings.xml /root/.m2/settings.xml

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
