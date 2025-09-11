# Webhook Handler

`cashu-gateway-webhook` exposes a servlet at `/webhook` for processing payment notifications.

## Request Parameters (phoenixd)

| Name | Required | Description |
|------|----------|-------------|
| `type` | Yes | Expected value `payment_received`. |
| `amountSat` | Yes | Payment amount in satoshis. |
| `paymentHash` | Yes | Lightning payment hash. |
| `externalId` | Yes | Lightning invoice identifier used to look up the quote. |

The webhook expects parameters in an `application/x-www-form-urlencoded` payload.

### Example

```
POST /webhook
type=payment_received&amountSat=1000&paymentHash=<hash>&externalId=<invoice>
```

## Validation Rules

Requests are validated as follows:

1. Retrieve the quote by `externalId` and ensure it exists and has direction `RECEIVE`.
2. Load the payment linked to the quote.
3. Confirm `paymentHash` and `amountSat` match the stored payment.
4. Verify the payment state is `PAID` and the webhook `type` is `payment_received`.

If validation succeeds, the payment is marked `CONFIRMED` and a `201 Created` response is returned. Any failure results in `401 Unauthorized`.
