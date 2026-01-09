# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -q package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
ENV CASHU_GATEWAY_PORT=8080
ENV VAULT_BASE_URL=http://localhost:${CASHU_GATEWAY_PORT}
COPY --from=build /build/target/cashu-gateway-rest-*.jar /app/app.jar
EXPOSE ${CASHU_GATEWAY_PORT}
# Pass configurable JVM system properties from environment
# - cashu_gateway_port: align Spring Boot server.port with env-provided port
ENTRYPOINT ["sh","-c","exec java -Dcashu_gateway_port=${CASHU_GATEWAY_PORT} -jar /app/app.jar"]
