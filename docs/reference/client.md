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

## PaymentClient

| Method | Inputs | Response | Description |
|--------|--------|----------|-------------|
| `getByQuoteId(String quoteId)` | Quote id | `GatewayPayment` linked to the quote. |
| `updatePayment(GatewayPayment payment)` | Updated payment | `GatewayPayment` after update. |

## QuoteClient

| Method | Inputs | Response | Description |
|--------|--------|----------|-------------|
| `getByInvoiceId(String invoiceId)` | Lightning invoice id | Matching `GatewayQuote`. |
