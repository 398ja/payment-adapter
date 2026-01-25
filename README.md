# Payment Adapter

Payment Adapter provides a RESTful service for creating and settling Lightning Network invoices. The project is organised as modular Maven components.

## Modules

| Module | Description |
| ------ | ----------- |
| **payment-adapter-model** | Domain entities and Spring Data JPA configuration. |
| **payment-adapter-rest** | Spring Boot application exposing REST endpoints for `GatewayQuote` and `GatewayPayment` entities. |
| **payment-adapter-client** | Small Java client for interacting with the REST service. |
| **payment-adapter-phoenixd** | Implementation of the `Gateway` interface that communicates with a [phoenixd](https://github.com/ACINQ/phoenixd) node. |
| **payment-adapter-webhook** | Servlet application for processing incoming webhook callbacks. |
| **payment-adapter-dummy** | Simple mock implementation of the `Gateway` interface used for testing. |

```
/ payment-adapter-model      Domain model and JPA entities
/ payment-adapter-rest       Spring Boot REST service
/ payment-adapter-client     REST client library
/ payment-adapter-phoenixd   phoenixd integration of Gateway
/ payment-adapter-webhook    Servlet webhook handler
/ payment-adapter-dummy      Dummy Gateway implementation
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
./mvnw -pl payment-adapter-rest package
```

## Running the REST Service

A `docker-compose.yml` file is provided to start PostgreSQL, phoenixd and the REST service. After Docker and Docker Compose are installed, simply run:

```bash
docker-compose up
```

This will start the following containers:

* **payment-adapter-db** – PostgreSQL database on port `5432`.
* **phoenixd** – phoenixd Lightning node on port `9740`.
* **payment-adapter-rest** – Spring Boot application exposing HTTP on port `8080`.
* **payment-adapter-webhook** – Servlet container handling webhooks on host port `9090` (container port `8080`). Built via module Dockerfile.

The REST application can also be launched directly using Maven:

```bash
./mvnw -pl payment-adapter-rest spring-boot:run
```

### Webhook service (Docker)

The webhook handler runs as a separate servlet container. In Docker Compose:

- The REST service is reachable as `http://payment-adapter-rest:8080`.
- The webhook service is reachable as `http://payment-adapter-webhook:8080/webhook/phoenixd` within the network and on the host as `http://localhost:${WEBHOOK_PORT:-9090}/webhook/phoenixd`.
- The REST app is configured to provide this webhook URL to phoenixd via `WEBHOOK_BASE_URL` environment variable.
- Compose builds the webhook image from `payment-adapter-webhook/Dockerfile`.
  The container also exposes `GET /health` which Docker Compose uses for health checks.

Webhook identification (wid) removed

Earlier versions mentioned a `wid` (webhook id) used to route webhook requests. This has been removed to simplify configuration.
The webhook handler expects phoenixd-formatted parameters (e.g., `type`, `amountSat`, `paymentHash`, `externalId`) at `/webhook/phoenixd` without any id parameter.

Database connection properties can be overridden via environment variables. In `docker-compose.yml` these are set as:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://payment-adapter-db:5432/payment-adapter
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

The `payment-adapter-client` module demonstrates basic interaction with these endpoints; see the [API reference](docs/reference/api.md) for payload details.

## Webhook Handler

The `payment-adapter-webhook` module provides a simple servlet mapped at `/webhook/phoenixd`. `PhoenixWebhookValidator` validates requests originating from phoenixd and updates payments through the REST client. No `wid` parameter is required.

## Running Tests

Unit tests can be executed with:

```bash
./mvnw test
```

Running `./mvnw test` at the project root will also produce an aggregated JaCoCo
coverage report under `target/site/jacoco-aggregate/index.html`.

## Dockerfile

A Dockerfile for the REST service is available under `payment-adapter-rest/Dockerfile`. It performs a two-stage build using the Maven base image and produces a runnable JAR:

```Dockerfile
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN ./mvnw -pl payment-adapter-rest -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/payment-adapter-rest/target/payment-adapter-rest-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

## Container Publishing

The `payment-adapter-rest` and `payment-adapter-webhook` modules include the [Jib](https://github.com/GoogleContainerTools/jib) Maven plugin to build and publish images to a Docker registry. Running:

```bash
./mvnw deploy
```

builds all modules and pushes:

- `docker.398ja.xyz/payment-adapter-rest` (tags: project version, latest)
- `docker.398ja.xyz/payment-adapter-webhook` (tags: project version, latest)

Authentication can be configured via `~/.m2/settings.xml` (server id `docker-hub` or your private registry), environment variables, or Jib's system properties. See Jib docs for details.

## Configuration

| Module | Option / Variable | Description |
| ------ | ----------------- | ----------- |
| **payment-adapter-rest** | `SPRING_DATASOURCE_URL` | JDBC connection string. |
| | `SPRING_DATASOURCE_USERNAME` | Database user. |
| | `SPRING_DATASOURCE_PASSWORD` | Database password. |
| **payment-adapter-phoenixd** | `phoenixd.currency` | Invoice currency unit. |
| | `phoenixd.expiration` | Quote lifetime in seconds. |
| | `phoenixd.fee.percent` | Percentage fee. |
| | `phoenixd.fee.fixed` | Fixed fee. |
| | `phoenixd.expiry` | Invoice expiry in seconds. |
| | `phoenixd.lnaddress` | Enable LN address support. |
| | `webhook.base_url` | Base URL for webhook callbacks; gateway name appended automatically. |
| **payment-adapter-dummy** | `dummy.payment_status` | Mock payment status. |
| | `dummy.amount` | Dummy payment amount. |
| | `dummy.expiry` | Quote expiry in seconds. |
| | `dummy.fee_reserve` | Fee reserve amount. |
| | `webhook.base_url` | Base URL for webhook callbacks; gateway name appended automatically. |

Each module reads configuration from its `app.properties` file or environment variables. See the guides in [docs](docs) for deployment details.

## License

This project currently does not include an explicit license. Contact the repository owner for usage terms.
