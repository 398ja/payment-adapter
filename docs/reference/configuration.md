# Configuration Reference

This document lists configuration properties and environment variables available across Payment Adapter modules.

## REST module ([payment-adapter-core/payment-adapter-rest](../../payment-adapter-core/payment-adapter-rest))

| Property | Default | Description |
| --- | --- | --- |
| `spring.application.name` | `payment-adapter-rest` | Spring Boot application name. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/payment-adapter` | JDBC connection string. |
| `spring.datasource.driverClassName` | `org.postgresql.Driver` | JDBC driver class. |
| `spring.datasource.username` | `postgres` | Database username. |
| `spring.datasource.password` | `password` | Database password. |
| `spring.jpa.database-platform` | `org.hibernate.dialect.PostgreSQLDialect` | Hibernate dialect. |
| `spring.jpa.hibernate.ddl-auto` | `create-drop` | JPA schema generation strategy (test). |
| `server.port` | `8080` | HTTP port (test). |

### Environment Variables

| Variable | Default | Description |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://payment-adapter-db:5432/payment-adapter` | Overrides database URL in Docker. |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Overrides database user in Docker. |
| `SPRING_DATASOURCE_PASSWORD` | `password` | Overrides database password in Docker. |
| `POSTGRES_DB` | `payment-adapter` | Database name for the PostgreSQL container. |
| `POSTGRES_USER` | `postgres` | Username for the PostgreSQL container. |
| `POSTGRES_PASSWORD` | `password` | Password for the PostgreSQL container. |

## Dummy Gateway module ([payment-adapter-ln/payment-adapter-ln-dummy](../../payment-adapter-ln/payment-adapter-ln-dummy))

| Property | Default | Description |
| --- | --- | --- |
| `dummy.payment_status` | `80` | Simulated payment status. |
| `dummy.amount` | `10` | Simulated invoice amount. |
| `dummy.expiry` | `86400` | Invoice expiry in seconds. |
| `dummy.fee_reserve` | `30` | Fee reserve amount. |
| `webhook.base_url` | `http://localhost:9090/webhook` | Base URL for webhook callbacks; gateway name appended automatically. |

## Phoenixd Gateway module ([payment-adapter-ln/payment-adapter-ln-phoenixd](../../payment-adapter-ln/payment-adapter-ln-phoenixd))

| Property | Default | Description |
| --- | --- | --- |
| `phoenixd.currency` | `sat` | Currency unit for invoices. |
| `phoenixd.expiration` | `86400` | Quote expiration in seconds. |
| `phoenixd.fee.percent` | `0.004` | Percentage fee applied. |
| `phoenixd.fee.fixed` | `4` | Fixed fee amount. |
| `phoenixd.expiry` | `60` | Invoice expiry in seconds. |
| `phoenixd.lnaddress` | `on` | Enable LN address support. |
| `phoenixd.payee` | `398ja@strike.me` | LN address for payouts (test). |
| `phoenixd.username` | *(empty)* | Basic auth username. |
| `phoenixd.password` | *(configured)* | Basic auth password. |
| `phoenixd.base_url` | `http://localhost:9740` | Base URL of phoenixd node. |
| `phoenixd.timeout` | `5000` | HTTP timeout in milliseconds. |
| `phoenixd.webhook_secret` | *(empty)* | Secret for verifying webhooks. |
| `webhook.base_url` | `http://localhost:9090/webhook` | Base URL for webhook callbacks; gateway name appended automatically. |

## Cash Gateway module ([payment-adapter-cash/payment-adapter-cash-gateway](../../payment-adapter-cash/payment-adapter-cash-gateway))

Configuration is read from `cash.properties` on the classpath.

| Property | Default | Description |
| --- | --- | --- |
| `cash.default.expiry` | `300` | Default invoice expiry in seconds (5 minutes). |
| `cash.default.relays` | `wss://relay.damus.io,wss://nos.lol` | Default relay URLs (comma-separated). |
| `cash.proof.length` | `4` | Proof code length (4–6 digits recommended). |
| `cash.subscriber.enabled` | `true` | Enable the Nostr event subscriber. |
| `cash.subscriber.expiry-check-interval` | `30000` | Interval for checking expired invoices (milliseconds). |

## Webhook module ([payment-adapter-webhook](../../payment-adapter-webhook))

| Property | Default | Description |
| --- | --- | --- |
| `gateway.api.base_url` | `http://localhost:8080` | REST API base URL for the webhook handler to call back. |

### Mint Webhook Forwarder

| Property | Default | Description |
| --- | --- | --- |
| `mint.webhook.enabled` | `true` | Enable/disable payment forwarding to cashu-mint. |
| `mint.webhook.url` | `http://localhost:7777/webhook/payment` | Cashu-mint webhook endpoint URL. |
| `mint.webhook.secret` | *(empty)* | HMAC-SHA256 secret for signature authentication. |
| `mint.webhook.timeout-ms` | `5000` | HTTP request timeout in milliseconds. |
| `mint.webhook.retry.max-attempts` | `3` | Maximum retry attempts on failure. |
| `mint.webhook.retry.initial-delay-ms` | `1000` | Initial retry delay in milliseconds. |
| `mint.webhook.retry.multiplier` | `2.0` | Exponential backoff multiplier. |

Each module reads configuration from its properties file or environment variables. See the guides in [docs](../) for deployment details.
