# Java Client

The `payment-gateway-client` module offers REST clients for interacting with the service.

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

- Example overrides:
  - Java: `new QuoteClient(/* uses defaults */)` or `new QuoteClient("http://api:8080")`
  - JVM arg: `-Dgateway.api.base_url=http://api:8080`
  - Env var: `GATEWAY_API_BASE_URL=http://api:8080`

### Docker Compose (cashu-mint)

- Set the client environment variables on the container that uses the client (e.g., `cashu-mint-rest`), not only on the server (`payment-gateway-rest`).
- Example service configuration in cashu-mint's `docker-compose.yml`:

  cashu-mint-rest:
    environment:
      GATEWAY_API_BASE_URL: http://payment-gateway-rest
      GATEWAY_API_PORT: 8080
    depends_on:
      payment-gateway-rest:
        condition: service_healthy

- Ensure both services are on the same Docker network so the hostname `payment-gateway-rest` resolves.

## PaymentClient

| Method | Inputs | Response | Description |
|--------|--------|----------|-------------|
| `getByQuoteId(String quoteId)` | Quote id | `GatewayPayment` linked to the quote. |
| `updatePayment(GatewayPayment payment)` | Updated payment | `GatewayPayment` after update. |

## QuoteClient

| Method | Inputs | Response | Description |
|--------|--------|----------|-------------|
| `getByInvoiceId(String invoiceId)` | Lightning invoice id | Matching `GatewayQuote`. |
