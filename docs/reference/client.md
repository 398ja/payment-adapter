# Java Client

The `cashu-gateway-client` module offers REST clients for interacting with the service.

## Common Methods

Available on all client classes.

| Method | Inputs | Response |
|--------|--------|----------|
| `get(Long id)` | Numeric identifier | Returns the entity with the given id or `null`. |
| `getByEntityId(String entityId)` | External id (`paymentId` or `quoteId`) | Returns the matching entity or `null`. |
| `create(T entity)` | Entity instance | Persists the entity and returns the created object. |
| `delete(Long id)` | Numeric identifier | Deletes the entity, response is empty. |

## Configuration

- Base URL precedence (first match wins):
  - Constructor argument
  - System property `gateway.api.base_url`
  - Environment variable `GATEWAY_API_BASE_URL`
  - Classpath file `gateway.properties` (key `gateway.api.base_url`)
  - Default `http://localhost:8080`

- Port configuration (applied when the resolved base URL has no explicit port or when a host-only value is used):
  - System property `gateway.api.port`
  - Environment variable `GATEWAY_API_PORT`
  - Classpath file `gateway.properties` (key `gateway.api.port`)
  - Default `8080`

- Example overrides:
  - Java: `new QuoteClient(/* uses defaults */)` or `new QuoteClient("http://api")` or `new QuoteClient("http://api:9090")`
  - JVM args: `-Dgateway.api.base_url=http://api -Dgateway.api.port=9090`
  - Env vars: `GATEWAY_API_BASE_URL=http://api` and `GATEWAY_API_PORT=9090`
  - Classpath file: provide `docs/examples/gateway.properties` on your runtime classpath. Example: [`gateway.properties`](../examples/gateway.properties)

### Docker Compose (cashu-mint)

- Set the client environment variables on the container that uses the client (e.g., `cashu-mint-rest`), not only on the server (`cashu-gateway-rest`).
- Example service configuration in cashu-mint's `docker-compose.yml`:

  cashu-mint-rest:
    environment:
      GATEWAY_API_BASE_URL: http://cashu-gateway-rest
      GATEWAY_API_PORT: 8080
    depends_on:
      cashu-gateway-rest:
        condition: service_healthy

- Ensure both services are on the same Docker network so the hostname `cashu-gateway-rest` resolves.

## PaymentClient

| Method | Inputs | Response | Description |
|--------|--------|----------|-------------|
| `getByQuoteId(String quoteId)` | Quote id | `GatewayPayment` linked to the quote. |
| `updatePayment(GatewayPayment payment)` | Updated payment | `GatewayPayment` after update. |

## QuoteClient

| Method | Inputs | Response | Description |
|--------|--------|----------|-------------|
| `getByInvoiceId(String invoiceId)` | Lightning invoice id | Matching `GatewayQuote`. |
