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
ENV PAYMENT_ADAPTER_PORT=8080
ENV VAULT_BASE_URL=http://localhost:${PAYMENT_ADAPTER_PORT}
COPY --from=build /build/payment-adapter-core/payment-adapter-rest/target/payment-adapter-rest-*.jar /app/app.jar
EXPOSE ${PAYMENT_ADAPTER_PORT}
# Pass configurable JVM system properties from environment
# - payment_gateway_port: align Spring Boot server.port with env-provided port
ENTRYPOINT ["sh","-c","exec java -Dpayment_gateway_port=${PAYMENT_ADAPTER_PORT} -jar /app/app.jar"]
