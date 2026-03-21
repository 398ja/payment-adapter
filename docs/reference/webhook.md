# Webhook Handler

The `payment-adapter-webhook` module provides a servlet-based webhook handler that dispatches incoming payment notifications to registered handlers via the `WebhookHandler` SPI.

## Health

The webhook service exposes a simple health endpoint for container orchestration:

- `GET /health` → returns `200 OK` with body `OK`.

Docker Compose uses this endpoint for health checks.

## Lightning Webhook (phoenixd)

`POST /webhook/phoenixd` processes payment notifications from phoenixd.

### Request Parameters

| Name | Required | Description |
|------|----------|-------------|
| `type` | Yes | Expected value `payment_received`. |
| `amountSat` | Yes | Payment amount in satoshis. |
| `paymentHash` | Yes | Lightning payment hash. |
| `externalId` | Yes | Lightning invoice identifier used to look up the quote. |

The webhook expects parameters in an `application/x-www-form-urlencoded` payload.

### Example

```
POST /webhook/phoenixd
type=payment_received&amountSat=1000&paymentHash=<hash>&externalId=<invoice>
```

### Validation Rules

1. Retrieve the quote by `externalId` and ensure it exists and has direction `RECEIVE`.
2. Load the payment linked to the quote.
3. Confirm `paymentHash` and `amountSat` match the stored payment.
4. Verify the payment state is `PAID` and the webhook `type` is `payment_received`.

If validation succeeds, the payment is marked `CONFIRMED` and a `201 Created` response is returned. Any failure results in `401 Unauthorized`.

## Cash Webhook

`POST /webhook/cash` processes Nostr kind 5201 CashIntent events from customers.

### Request Body

The webhook expects a JSON body containing the Nostr event:

```json
{
  "id": "<event-id>",
  "pubkey": "<customer-pubkey>",
  "created_at": 1712345650,
  "kind": 5201,
  "tags": [
    ["ref", "6f2c1d"]
  ],
  "content": "<NIP-44 encrypted payload>",
  "sig": "<signature>",
  "decrypted_content": "{\"ref\":\"6f2c1d\",\"from\":\"<customer-pubkey>\",\"proof\":\"4821\",\"ts\":1712345650}"
}
```

The `decrypted_content` field is optional; if provided (e.g., by a relay proxy), it is used to extract the proof code and customer timestamp.

### Validation Rules

1. Event kind must be `5201` (CashIntent).
2. `ref` tag must be present and be a 4–24 character hex string.
3. Event timestamp must not be more than 300 seconds in the future.
4. Event must not have been previously processed (duplicate detection by event ID).

On success, the associated cash invoice transitions to `INTENT_RECEIVED` and a `201 Created` response is returned.

## Webhook SPI Pattern

The webhook module uses a Service Provider Interface (SPI) pattern for extensibility. Each payment type registers a `WebhookHandler` implementation:

| Handler | Payment Type | Endpoint | Module |
|---------|-------------|----------|--------|
| `PhoenixWebhookHandler` | `phoenixd` | `/webhook/phoenixd` | `payment-adapter-ln-webhook` |
| `CashWebhookHandler` | `cash` | `/webhook/cash` | `payment-adapter-cash-webhook` |
| `StripeWebhookHandler` | `stripe` | `/webhook/stripe` | `payment-adapter-stripe-webhook` |

Handlers are discovered via `META-INF/services/xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler`.

## Mint Webhook Forwarder

The `MintWebhookForwarder` (added in v0.8.0) enables push-based payment notifications to cashu-mint. When a payment is confirmed via any webhook handler, the forwarder sends a `PaymentNotification` to the mint's webhook endpoint.

### Features

- HMAC-SHA256 signature authentication via `X-Webhook-Signature` header
- Retry logic with exponential backoff (configurable attempts and delays)
- Idempotency via `X-Idempotency-Key` header
- Supports both Lightning (`bolt11`) and cash payment methods

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `mint.webhook.enabled` | `true` | Enable/disable forwarding. |
| `mint.webhook.url` | `http://localhost:7777/webhook/payment` | Mint webhook endpoint URL. |
| `mint.webhook.secret` | *(empty)* | HMAC secret for signature authentication. |
| `mint.webhook.timeout-ms` | `5000` | HTTP request timeout in milliseconds. |
| `mint.webhook.retry.max-attempts` | `3` | Maximum retry attempts on failure. |
| `mint.webhook.retry.initial-delay-ms` | `1000` | Initial retry delay in milliseconds. |
| `mint.webhook.retry.multiplier` | `2.0` | Backoff multiplier for retry delay. |

### Notification Payload

```json
{
  "quoteId": "Q123",
  "paymentMethod": "bolt11",
  "amount": 1000,
  "preimage": "<payment-preimage>",
  "paidAt": "2026-02-01T12:00:00Z"
}
```

For cash payments, `paymentMethod` is `"cash"` and `receiptId` is included instead of `preimage`.

## Stripe Webhook

`POST /webhook/stripe` processes signed Stripe event payloads.

### Request Body

The webhook expects the raw JSON event body sent by Stripe. Signature
verification uses the `Stripe-Signature` header and the configured
`STRIPE_WEBHOOK_SECRET`.

### Supported Events

- `checkout.session.completed`
- `checkout.session.async_payment_succeeded`
- `charge.refunded`
- `charge.dispute.created`

### Validation Rules

1. The raw body must be present and parse as JSON.
2. The `Stripe-Signature` header must verify successfully within the configured tolerance window.
3. Event IDs are deduplicated using persisted `ProcessedStripeWebhookEvent` records.
4. Successful Checkout events must match the stored quote amount, currency, and Checkout Session ID.
5. Refund and dispute events update Stripe-native state only and do not roll the generic quote or payment state backward.
