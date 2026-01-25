# Configuration Reference

This document lists configuration properties and environment variables available across Payment Adapter modules.

## REST module ([payment-adapter-rest](../../payment-adapter-rest))

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
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db:5432/payment-adapter` | Overrides database URL in Docker. |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Overrides database user in Docker. |
| `SPRING_DATASOURCE_PASSWORD` | `password` | Overrides database password in Docker. |
| `POSTGRES_DB` | `payment-adapter` | Database name for the PostgreSQL container. |
| `POSTGRES_USER` | `postgres` | Username for the PostgreSQL container. |
| `POSTGRES_PASSWORD` | `password` | Password for the PostgreSQL container. |

## Dummy Gateway module ([payment-adapter-dummy](../../payment-adapter-dummy))

| Property | Default | Description |
| --- | --- | --- |
| `dummy.payment_status` | `80` | Simulated payment status. |
| `dummy.amount` | `10` | Simulated invoice amount. |
| `dummy.expiry` | `86400` | Invoice expiry in seconds. |
| `dummy.fee_reserve` | `30` | Fee reserve amount. |
| `webhook.base_url` | `http://localhost:9090/webhook` | Base URL for webhook callbacks; gateway name appended automatically. |

## Phoenixd Gateway module ([payment-adapter-phoenixd](../../payment-adapter-phoenixd))

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
| `phoenixd.password` | `49c6311a099407885c1b161f57c5c0ea4cb7cb2636a99488b870ffd8090a453a` | Basic auth password. |
| `phoenixd.base_url` | `http://localhost:9740` | Base URL of phoenixd node. |
| `phoenixd.timeout` | `5000` | HTTP timeout in milliseconds. |
| `phoenixd.webhook_secret` | *(empty)* | Secret for verifying webhooks. |
| `webhook.base_url` | `http://localhost:9090/webhook` | Base URL for webhook callbacks; gateway name appended automatically. |

## Webhook module ([payment-adapter-webhook](../../payment-adapter-webhook))

| Property | Default | Description |
| --- | --- | --- |
