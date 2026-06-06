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

## Stripe Gateway module ([payment-adapter-stripe/payment-adapter-stripe-gateway](../../payment-adapter-stripe/payment-adapter-stripe-gateway))

| Property | Default | Description |
| --- | --- | --- |
| `stripe.enabled` | `false` | Enables the Stripe gateway beans in the REST service. |
| `stripe.secret-key` | *(required when enabled)* | Stripe secret API key used for Checkout Session operations. |
| `stripe.success-url` | *(required when enabled)* | Redirect URL after successful Checkout completion. |
| `stripe.cancel-url` | *(required when enabled)* | Redirect URL after Checkout cancellation. |
| `stripe.default-currency` | `usd` | Default Stripe currency code in lowercase. |
| `stripe.allowed-currencies` | `usd` | Comma-separated allowlist of supported currencies. |
| `stripe.checkout-expiry-seconds` | `1800` | Checkout Session expiry in seconds. |
| `STRIPE_WEBHOOK_TOLERANCE_SECONDS` *(env var)* | `300` | Maximum accepted webhook signature timestamp age, in seconds. Read from the environment variable — the webhook signature verifier is instantiated via `ServiceLoader` (outside the Spring context), so it does **not** bind a Spring property. Paired with `STRIPE_WEBHOOK_SECRET`. |
| `stripe.min-amount-minor` | `1` | Minimum amount in minor currency units. |
| `stripe.max-amount-minor` | `2147483647` | Maximum amount in minor currency units. |

## Stripe Connect module ([payment-adapter-stripe/payment-adapter-stripe-connect](../../payment-adapter-stripe/payment-adapter-stripe-connect))

| Property | Default | Description |
| --- | --- | --- |
| `stripe.connect.enabled` | `false` | Enables Stripe Connect-specific services. |
| `stripe.connect.refresh-url` | *(empty)* | Refresh URL used during Connect onboarding. |
| `stripe.connect.return-url` | *(empty)* | Return URL used during Connect onboarding. |

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

### Stripe Webhook Runtime

| Variable | Default | Description |
| --- | --- | --- |
| `STRIPE_WEBHOOK_SECRET` | *(required for Stripe webhook processing)* | Secret used to verify the `Stripe-Signature` header. |
| `STRIPE_WEBHOOK_TOLERANCE_SECONDS` | `300` | Maximum accepted webhook signature age in seconds. |

Each module reads configuration from its properties file or environment variables. See the guides in [docs](../) for deployment details.
