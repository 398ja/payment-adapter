# Cashu Gateway

Cashu Gateway provides a RESTful service for creating and settling Lightning Network invoices. The project is organised as modular Maven components.

## Modules

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
* Maven 3.8+ (or use the included Maven Wrapper `./mvnw`)
* Docker (for running the provided containers)

## Building

To build all modules run the standard Maven build using the wrapper:

```bash
./mvnw package
```

Individual modules can be built with the `-pl` flag, for example:

```bash
./mvnw -pl cashu-gateway-rest package
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
* **cashu-gateway-webhook** – Servlet container handling webhooks on host port `9090` (container port `8080`). Built via module Dockerfile.

The REST application can also be launched directly using Maven:

```bash
./mvnw -pl cashu-gateway-rest spring-boot:run
```

### Webhook service (Docker)

The webhook handler runs as a separate servlet container. In Docker Compose:

- The REST service is reachable as `http://cashu-gateway-rest:8080`.
- The webhook service is reachable as `http://cashu-gateway-webhook:8080/webhook/phoenixd` within the network and on the host as `http://localhost:${WEBHOOK_PORT:-9090}/webhook/phoenixd`.
- The REST app is configured to provide this webhook URL to phoenixd via `WEBHOOK_BASE_URL` environment variable.
- Compose builds the webhook image from `cashu-gateway-webhook/Dockerfile`.
  The container also exposes `GET /health` which Docker Compose uses for health checks.

Webhook identification (wid) removed

Earlier versions mentioned a `wid` (webhook id) used to route webhook requests. This has been removed to simplify configuration.
The webhook handler expects phoenixd-formatted parameters (e.g., `type`, `amountSat`, `paymentHash`, `externalId`) at `/webhook/phoenixd` without any id parameter.

Database connection properties can be overridden via environment variables. In `docker-compose.yml` these are set as:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://cashu-gateway-db:5432/cashu-gateway
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

The `cashu-gateway-webhook` module provides a simple servlet mapped at `/webhook/phoenixd`. `PhoenixWebhookValidator` validates requests originating from phoenixd and updates payments through the REST client. No `wid` parameter is required.

## Running Tests

Integration tests reside in the `cashu-gateway-test` module and require a running phoenixd instance as well as the REST service. Execute them with:

```bash
./mvnw -pl cashu-gateway-test test
```

Running `./mvnw test` at the project root will also produce an aggregated JaCoCo
coverage report under `target/site/jacoco-aggregate/index.html`.

## Dockerfile

A Dockerfile for the REST service is available under `cashu-gateway-rest/Dockerfile`. It performs a two-stage build using the Maven base image and produces a runnable JAR:

```Dockerfile
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN ./mvnw -pl cashu-gateway-rest -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/cashu-gateway-rest/target/cashu-gateway-rest-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

## Container Publishing

The `cashu-gateway-rest` and `cashu-gateway-webhook` modules include the [Jib](https://github.com/GoogleContainerTools/jib) Maven plugin to build and publish images to a Docker registry. Running:

```bash
./mvnw deploy
```

builds all modules and pushes:

- `docker.398ja.xyz/cashu-gateway-rest` (tags: project version, latest)
- `docker.398ja.xyz/cashu-gateway-webhook` (tags: project version, latest)

Authentication can be configured via `~/.m2/settings.xml` (server id `docker-hub` or your private registry), environment variables, or Jib's system properties. See Jib docs for details.

## Configuration

| Module | Option / Variable | Description |
| ------ | ----------------- | ----------- |
| **cashu-gateway-rest** | `SPRING_DATASOURCE_URL` | JDBC connection string. |
| | `SPRING_DATASOURCE_USERNAME` | Database user. |
| | `SPRING_DATASOURCE_PASSWORD` | Database password. |
| **cashu-gateway-phoenixd** | `phoenixd.currency` | Invoice currency unit. |
| | `phoenixd.expiration` | Quote lifetime in seconds. |
| | `phoenixd.fee.percent` | Percentage fee. |
| | `phoenixd.fee.fixed` | Fixed fee. |
| | `phoenixd.expiry` | Invoice expiry in seconds. |
| | `phoenixd.lnaddress` | Enable LN address support. |
| | `webhook.base_url` | Base URL for webhook callbacks; gateway name appended automatically. |
| **cashu-gateway-dummy** | `dummy.payment_status` | Mock payment status. |
| | `dummy.amount` | Dummy payment amount. |
| | `dummy.expiry` | Quote expiry in seconds. |
| | `dummy.fee_reserve` | Fee reserve amount. |
| | `webhook.base_url` | Base URL for webhook callbacks; gateway name appended automatically. |

Each module reads configuration from its `app.properties` file or environment variables. See the guides in [docs](docs) for deployment details.

## License

This project currently does not include an explicit license. Contact the repository owner for usage terms.
