# Cashu Gateway

Cashu Gateway is a collection of Java modules that provide a simple gateway service for creating and settling Lightning Network invoices. It exposes a Spring Boot REST API backed by PostgreSQL and includes optional integrations such as a Phoenixd implementation and a servlet based webhook.

## Project Structure

This project is organised as a multi-module Maven build. The root `pom.xml` aggregates the following modules:

| Module | Description |
| ------ | ----------- |
| **cashu-gateway-model** | Domain entities and Spring Data JPA configuration. |
| **cashu-gateway-rest** | Spring Boot application exposing REST endpoints for `GatewayQuote` and `GatewayPayment` entities. |
| **cashu-gateway-client** | Small Java client for interacting with the REST service. |
| **cashu-gateway-phoenixd** | Implementation of the `Gateway` interface that communicates with a [phoenixd](https://github.com/ACINQ/phoenixd) node. |
| **cashu-gateway-webhook** | Servlet application for processing incoming webhook callbacks. |
| **cashu-gateway-dummy** | Simple mock implementation of the `Gateway` interface used for testing. |
| **cashu-gateway-test** | Integration tests that exercise the phoenixd gateway and REST API. |

```
/ cashu-gateway-model      Domain model and JPA entities
/ cashu-gateway-rest       Spring Boot REST service
/ cashu-gateway-client     REST client library
/ cashu-gateway-phoenixd   phoenixd integration of Gateway
/ cashu-gateway-webhook    Servlet webhook handler
/ cashu-gateway-dummy      Dummy Gateway implementation
/ cashu-gateway-test       Integration tests
```

## Requirements

* Java 21 or newer
* Maven 3.8+
* Docker (for running the provided containers)

## Building

To build all modules run the standard Maven build:

```bash
mvn package
```

Individual modules can be built with the `-pl` flag, for example:

```bash
mvn -pl cashu-gateway-rest package
```

## Running the REST Service

A `docker-compose.yml` file is provided to start PostgreSQL, phoenixd and the REST service. After Docker and Docker Compose are installed, simply run:

```bash
docker-compose up
```

This will start the following containers:

* **cashu-gateway-db** – PostgreSQL database on port `5432`.
* **phoenixd** – phoenixd Lightning node on port `9740`.
* **cashu-gateway-rest** – Spring Boot application exposing HTTP on port `8080`.

The REST application can also be launched directly using Maven:

```bash
mvn -pl cashu-gateway-rest spring-boot:run
```

Database connection properties can be overridden via environment variables. In `docker-compose.yml` these are set as:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/cashu-gateway
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=password
```

## API Overview

The REST layer is implemented using Spring Data REST. A full description of each endpoint is available in the [API reference](docs/reference/api.md). Once the service is running the following resources are available:

* `GET /quote` – list quotes
* `POST /quote` – create a quote
* `GET /quote/{id}` – fetch a quote by its numeric id
* `GET /quote/search/findByQuoteId?quoteId=...` – fetch a quote using its external quote id
* `GET /quote/search/findByInvoiceId?invoiceId=...` – find a quote by the Lightning invoice id

Likewise for payments:

* `GET /payment` – list payments
* `POST /payment` – create a payment
* `GET /payment/{id}` – fetch a payment by id
* `GET /payment/search/findByPaymentId?paymentId=...`
* `GET /payment/search/findByQuoteId?quoteId=...`

The `cashu-gateway-client` module demonstrates basic interaction with these endpoints; see the [API reference](docs/reference/api.md) for payload details.

## Webhook Handler

The `cashu-gateway-webhook` module provides a simple servlet mapped at `/webhook`. `PhoenixWebhookValidator` validates requests originating from phoenixd and updates payments through the REST client. Requests must include a `wid` parameter which identifies the type of webhook request to validate. See the [API reference](docs/reference/api.md) for the underlying REST endpoints.

## Running Tests

Integration tests reside in the `cashu-gateway-test` module and require a running phoenixd instance as well as the REST service. Execute them with:

```bash
mvn -pl cashu-gateway-test test
```

Running `mvn test` at the project root will also produce an aggregated JaCoCo
coverage report under `target/site/jacoco-aggregate/index.html`.

## Dockerfile

A Dockerfile for the REST service is available under `cashu-gateway-rest/Dockerfile`. It performs a two-stage build using the Maven base image and produces a runnable JAR:

```Dockerfile
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -pl cashu-gateway-rest -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/cashu-gateway-rest/target/cashu-gateway-rest-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

## Configuration

Each module that implements the `Gateway` interface provides its own `app.properties` file containing configuration options. For example `cashu-gateway-phoenixd` defines settings for invoice expiry and webhook URLs, while `cashu-gateway-dummy` exposes simple dummy values. Adjust these files to suit your environment.

## License

This project currently does not include an explicit license file. If you plan to use it in production or as the basis of other work, please consult the repository owner.

