# Cashu Gateway

Cashu Gateway provides a RESTful service for creating and settling Lightning Network invoices. The project is organised as modular Maven components.

## Modules

| Module | Description |
| ------ | ----------- |
| **cashu-gateway-model** | Domain entities and Spring Data JPA configuration. |
| **cashu-gateway-rest** | Spring Boot REST API exposing `GatewayQuote` and `GatewayPayment` resources. |
| **cashu-gateway-client** | Java client for interacting with the REST API. |
| **cashu-gateway-phoenixd** | phoenixd-backed implementation of the `Gateway` interface. |
| **cashu-gateway-webhook** | Servlet handler for webhook callbacks. |
| **cashu-gateway-dummy** | Mock implementation of the `Gateway` interface. |
| **cashu-gateway-test** | Integration tests for the REST API and phoenixd gateway. |

Guides for [requirements](docs/requirements.md), [building](docs/building.md), [running](docs/running.md), [testing](docs/testing.md) and [Docker usage](docs/docker.md) are available in the `docs` directory.

## REST API

| Method | Endpoint | Parameters | Response |
| ------ | -------- | ---------- | -------- |
| GET | `/quote` | – | List of quotes. |
| POST | `/quote` | JSON `GatewayQuote` | Created quote. |
| GET | `/quote/{id}` | `id` path variable | Quote by numeric ID. |
| GET | `/quote/search/findByQuoteId` | `quoteId` query | Quote by external ID. |
| GET | `/quote/search/findByInvoiceId` | `invoiceId` query | Quote by invoice ID. |
| GET | `/payment` | – | List of payments. |
| POST | `/payment` | JSON `GatewayPayment` | Created payment. |
| GET | `/payment/{id}` | `id` path variable | Payment by numeric ID. |
| GET | `/payment/search/findByPaymentId` | `paymentId` query | Payment by external ID. |
| GET | `/payment/search/findByQuoteId` | `quoteId` query | Payment by associated quote. |

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
| | `<wid>.wid` | Webhook identifier mapping. |
| | `webhook.base_url` | Base URL for webhook callbacks. |
| **cashu-gateway-dummy** | `dummy.payment_status` | Mock payment status. |
| | `dummy.amount` | Dummy payment amount. |
| | `dummy.expiry` | Quote expiry in seconds. |
| | `dummy.fee_reserve` | Fee reserve amount. |
| | `webhook.base_url` | Base URL for webhook callbacks. |

Each module reads configuration from its `app.properties` file or environment variables. See the guides in [docs](docs) for deployment details.

## License

This project currently does not include an explicit license. Contact the repository owner for usage terms.
