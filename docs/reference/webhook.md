# Webhook Handler

`cashu-gateway-webhook` exposes a servlet at `/webhook` for processing payment notifications.

## Request Parameters

| Name | Required | Description |
|------|----------|-------------|
| `wid` | Yes | Identifies the webhook source (`phoenixd` or `dummy`). If absent in the request, the system property `wid` is used. |
| `type` | Yes (phoenixd) | Expected value `payment_received`. |
| `amountSat` | Yes (phoenixd) | Payment amount in satoshis. |
| `paymentHash` | Yes (phoenixd) | Lightning payment hash. |
| `externalId` | Yes (phoenixd) | Lightning invoice identifier used to look up the quote. |

The webhook expects parameters in an `application/x-www-form-urlencoded` payload.

### Example

```
POST /webhook
wid=phoenixd&type=payment_received&amountSat=1000&paymentHash=<hash>&externalId=<invoice>
```

## Validation Rules

Requests with `wid=phoenixd` are validated as follows:

1. Retrieve the quote by `externalId` and ensure it exists and has direction `RECEIVE`.
2. Load the payment linked to the quote.
3. Confirm `paymentHash` and `amountSat` match the stored payment.
4. Verify the payment state is `PAID` and the webhook `type` is `payment_received`.

If validation succeeds, the payment is marked `CONFIRMED` and a `201 Created` response is returned. Any failure results in `401 Unauthorized`.

The `dummy` webhook id is provided for testing and creates a placeholder payment without validation.
