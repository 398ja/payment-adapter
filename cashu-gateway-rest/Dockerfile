FROM maven:3.9.7-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -B -pl cashu-gateway-rest -am package -DskipTests

FROM openjdk:21-slim
WORKDIR /app
COPY --from=build /build/cashu-gateway-rest/target/cashu-gateway-rest-*.jar cashu-gateway-rest.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "cashu-gateway-rest.jar"]
