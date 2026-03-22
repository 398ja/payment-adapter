# Payment Adapter

Payment Adapter provides RESTful services for creating and settling Lightning Network invoices, hosted Stripe Checkout payments, and NIP-XX Cash Payments over Nostr. The project is organised as modular Maven components grouped into multiple aggregators.

## Modules

| Aggregator | Module | Description |
| ---------- | ------ | ----------- |
| **payment-adapter-core** | payment-adapter-model | Domain entities and Spring Data JPA configuration. |
| | payment-adapter-common | `Gateway` interface and shared exceptions. |
| | payment-adapter-rest | Spring Boot application exposing REST endpoints (port 8080). |
| | payment-adapter-client | Java HTTP client library for the REST service. |
| **payment-adapter-ln** | payment-adapter-ln-phoenixd | `Gateway` implementation for [phoenixd](https://github.com/ACINQ/phoenixd). |
| | payment-adapter-ln-dummy | Mock `Gateway` for testing. |
| | payment-adapter-ln-webhook | Lightning webhook handler. |
| **payment-adapter-cash** | payment-adapter-cash-nostr | Nostr event types (kinds 5200–5204), `nostr+cash://` URI codec. |
| | payment-adapter-cash-gateway | `CashGateway` implementation, QR code generation, REST controller. |
| | payment-adapter-cash-webhook | `CashWebhookHandler` for processing kind 5201 intents. |
| **payment-adapter-stripe** | payment-adapter-stripe-gateway | `StripeGateway` implementation for hosted Checkout Session payments. |
| | payment-adapter-stripe-webhook | `StripeWebhookHandler` for Stripe payment events. |
| | payment-adapter-stripe-connect | Merchant account linking and Stripe Connect helpers. |
| **payment-adapter-webhook** | *(single module)* | Servlet webhook handler with SPI dispatch and `MintWebhookForwarder`. |

```
payment-adapter/
├── payment-adapter-core/
│   ├── payment-adapter-model          Domain model and JPA entities
│   ├── payment-adapter-common         Gateway interface and shared types
│   ├── payment-adapter-rest           Spring Boot REST service
│   └── payment-adapter-client         REST client library
├── payment-adapter-ln/
│   ├── payment-adapter-ln-phoenixd    phoenixd Gateway implementation
│   ├── payment-adapter-ln-dummy       Dummy Gateway for testing
│   └── payment-adapter-ln-webhook     Lightning webhook handler
├── payment-adapter-cash/
│   ├── payment-adapter-cash-nostr     Nostr events and URI codec
│   ├── payment-adapter-cash-gateway   Cash Gateway, QR, REST controller
│   └── payment-adapter-cash-webhook   Cash webhook handler
├── payment-adapter-stripe/
│   ├── payment-adapter-stripe-gateway Stripe Checkout gateway implementation
│   ├── payment-adapter-stripe-webhook Stripe webhook handler
│   └── payment-adapter-stripe-connect Stripe Connect support services
└── payment-adapter-webhook            Servlet webhook handler (port 9090)
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

Full verification (build + test + integration test):

```bash
./mvnw -q verify
```

Individual modules can be built with the `-pl` flag, for example:

```bash
./mvnw -pl payment-adapter-core/payment-adapter-rest -am package
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
./mvnw -pl payment-adapter-core/payment-adapter-rest spring-boot:run
```

### Webhook service (Docker)

The webhook handler runs as a separate servlet container. In Docker Compose:

- The REST service is reachable as `http://payment-adapter-rest:8080`.
- The webhook service is reachable as `http://payment-adapter-webhook:8080/webhook/phoenixd` within the network and on the host as `http://localhost:${WEBHOOK_PORT:-9090}/webhook/phoenixd`.
- The REST app is configured to provide this webhook URL to phoenixd via `WEBHOOK_BASE_URL` environment variable.
- Compose builds the webhook image from `payment-adapter-webhook/Dockerfile`.
  The container also exposes `GET /health` which Docker Compose uses for health checks.

Database connection properties can be overridden via environment variables. In `docker-compose.yml` these are set as:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://payment-adapter-db:5432/payment-adapter
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=password
```

## API Overview

### Lightning Payments

The Lightning REST layer is implemented using Spring Data REST. A full description of each endpoint is available in the [API reference](docs/reference/api.md). Once the service is running the following resources are available:

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

### Cash Payments

Cash payments use the NIP-XX protocol over Nostr for in-person cash transactions. See [Cash Payments reference](docs/reference/cash-payments.md) for protocol details.

* `POST /cash/invoice` – create a cash invoice (returns QR code)
* `GET /cash/invoice/{ref}` – get invoice status
* `POST /cash/invoice/{ref}/confirm` – confirm cash received
* `POST /cash/invoice/{ref}/cancel` – cancel invoice
* `GET /cash/invoice/{ref}/qr` – get QR code as PNG image
* `GET /cash/invoice/{ref}/qr-payload` – get raw `nostr+cash://` URI

### Stripe Payments

Stripe payments use hosted Checkout Sessions and are confirmed by signed webhook events. The adapter persists the Checkout Session URL on the quote and tracks Stripe-native identifiers separately from Lightning fields.

## Webhook Handler

The `payment-adapter-webhook` module uses a SPI pattern to dispatch webhooks to registered handlers:

- **Lightning:** `/webhook/phoenixd` – processes phoenixd payment notifications
- **Cash:** `/webhook/cash` – processes Nostr kind 5201 CashIntent events
- **Stripe:** `/webhook/stripe` – processes signed Stripe Checkout and charge events

The `MintWebhookForwarder` (v0.8.0) forwards confirmed payments to cashu-mint for real-time quote status updates. See [Webhook reference](docs/reference/webhook.md) for details.

## Running Tests

Unit tests can be executed with:

```bash
./mvnw test
```

Full verification:

```bash
./mvnw -q verify
```

Running `./mvnw test` at the project root will also produce an aggregated JaCoCo
coverage report under `target/site/jacoco-aggregate/index.html`.

## Dockerfile

A Dockerfile for the REST service is available under `payment-adapter-core/payment-adapter-rest/Dockerfile`. It performs a two-stage build using the Maven base image and produces a runnable JAR:

```Dockerfile
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN ./mvnw -pl payment-adapter-core/payment-adapter-rest -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/payment-adapter-core/payment-adapter-rest/target/payment-adapter-rest-*.jar app.jar
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
| **payment-adapter-ln-phoenixd** | `phoenixd.currency` | Invoice currency unit. |
| | `phoenixd.expiration` | Quote lifetime in seconds. |
| | `phoenixd.fee.percent` | Percentage fee. |
| | `phoenixd.fee.fixed` | Fixed fee. |
| | `phoenixd.expiry` | Invoice expiry in seconds. |
| | `phoenixd.lnaddress` | Enable LN address support. |
| | `webhook.base_url` | Base URL for webhook callbacks; gateway name appended automatically. |
| **payment-adapter-ln-dummy** | `dummy.payment_status` | Mock payment status. |
| | `dummy.amount` | Dummy payment amount. |
| | `dummy.expiry` | Quote expiry in seconds. |
| | `dummy.fee_reserve` | Fee reserve amount. |
| | `webhook.base_url` | Base URL for webhook callbacks; gateway name appended automatically. |
| **payment-adapter-cash-gateway** | `cash.default.expiry` | Invoice expiry in seconds (default 300). |
| | `cash.default.relays` | Default relay URLs (comma-separated). |
| | `cash.proof.length` | Proof code length (default 4). |
| | `cash.subscriber.enabled` | Enable Nostr event subscriber. |
| **payment-adapter-stripe-gateway** | `stripe.enabled` | Enable the Stripe gateway integration. |
| | `stripe.secret-key` | Stripe secret API key. |
| | `stripe.success-url` | Checkout success redirect URL. |
| | `stripe.cancel-url` | Checkout cancel redirect URL. |
| | `stripe.default-currency` | Default Stripe currency in minor units. |
| | `stripe.checkout-expiry-seconds` | Checkout Session expiry. |
| **payment-adapter-webhook** | `mint.webhook.url` | Cashu-mint webhook endpoint for payment forwarding. |
| | `mint.webhook.secret` | HMAC secret for webhook signature. |
| | `mint.webhook.enabled` | Enable/disable mint forwarding (default true). |

Each module reads configuration from its properties file or environment variables. See the [Configuration reference](docs/reference/configuration.md) for full details.

## License

This project currently does not include an explicit license. Contact the repository owner for usage terms.
