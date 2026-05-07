FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/monopoly-backend.jar monopoly-backend.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "monopoly-backend.jar"]
