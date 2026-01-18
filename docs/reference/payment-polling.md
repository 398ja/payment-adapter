# Payment Status Polling

This document describes how to poll for payment status when using production-like mode (autopay disabled) with `phoenixd-mock`.

## Overview

When `PHOENIXD_AUTOPAY_ENABLED=false`, invoices are not automatically settled. Clients must:

1. Receive the BOLT11 invoice from the quote response
2. Pay the invoice externally (or simulate via `/mockpay`)
3. Poll the payment status until it transitions to `PAID`

## Polling Endpoints

### Get Payment by Quote ID

```
GET /payment/search/findByQuoteId?quoteId={quoteId}
```

Returns the payment record associated with the quote.

**Request:**

```bash
curl "http://localhost:8080/payment/search/findByQuoteId?quoteId=Q123"
```

**Response:**

```json
{
    "id": 1,
    "request": "lnbc1...",
    "paymentId": "P123",
    "quoteId": "Q123",
    "state": "PENDING",
    "paidDate": null,
    "confirmedDate": null,
    "sourceCurrency": "sat",
    "amount": 1000,
    "lightningNetworkFee": 0,
    "totalAmount": 1000,
    "paymentHash": "hash123...",
    "paymentPreimage": null
}
```

**Key Fields:**

| Field | Description |
|-------|-------------|
| `state` | Current payment state: `PENDING`, `PAID`, or `CONFIRMED` |
| `paidDate` | Timestamp when payment was received (null if pending) |
| `confirmedDate` | Timestamp when payment was confirmed (null if not confirmed) |

### Get Quote by Quote ID

```
GET /quote/search/findByQuoteId?quoteId={quoteId}
```

Returns the quote including the BOLT11 invoice.

**Request:**

```bash
curl "http://localhost:8080/quote/search/findByQuoteId?quoteId=Q123"
```

**Response:**

```json
{
    "id": 1,
    "quoteId": "Q123",
    "invoiceId": "INV123",
    "expiry": 3600,
    "description": "Mint 1000 sats",
    "request": "lnbc10n1p...",
    "amount": 1000,
    "unit": "sat",
    "state": "PENDING",
    "direction": "RECEIVE"
}
```

**Key Fields:**

| Field | Description |
|-------|-------------|
| `request` | BOLT11 invoice to pay |
| `expiry` | Invoice expiry in seconds |
| `state` | Quote state (mirrors payment state) |

## State Transitions

```
┌─────────┐        ┌────────┐        ┌───────────┐
│ PENDING │ ────► │  PAID  │ ────► │ CONFIRMED │
└─────────┘        └────────┘        └───────────┘
     │                  │                   │
     │                  │                   │
 Invoice          Payment            Webhook
 created          received           processed
```

**State Definitions:**

| State | Description |
|-------|-------------|
| `PENDING` | Invoice created, awaiting payment |
| `PAID` | Payment received by Lightning node |
| `CONFIRMED` | Payment confirmed via webhook |

## Polling Strategy

### Recommended Approach

```java
// Pseudocode for polling
int maxAttempts = 60;       // Maximum attempts
int intervalMs = 2000;      // Poll every 2 seconds
int attempt = 0;

while (attempt < maxAttempts) {
    Payment payment = getPaymentByQuoteId(quoteId);

    if (payment.getState() == State.PAID ||
        payment.getState() == State.CONFIRMED) {
        // Payment complete - proceed with minting
        return payment;
    }

    Thread.sleep(intervalMs);
    attempt++;
}

throw new TimeoutException("Payment not received within timeout");
```

### Configuration Parameters

| Parameter | Recommended Value | Description |
|-----------|-------------------|-------------|
| Poll Interval | 2-5 seconds | Time between status checks |
| Max Duration | Quote expiry time | Stop polling when quote expires |
| Max Attempts | 60-120 | Based on interval and max duration |

### Exponential Backoff (Optional)

For high-load scenarios, implement exponential backoff:

```java
int intervalMs = 1000;  // Start with 1 second
int maxIntervalMs = 10000;  // Cap at 10 seconds

while (notPaid && !expired) {
    Payment payment = getPaymentByQuoteId(quoteId);

    if (isPaid(payment)) {
        return payment;
    }

    Thread.sleep(intervalMs);
    intervalMs = Math.min(intervalMs * 2, maxIntervalMs);
}
```

## Complete Flow Example

### 1. Create Quote and Get Invoice

```bash
# Create a mint quote (this is typically done by the gateway internally)
QUOTE=$(curl -s -X POST http://localhost:8080/quote \
    -H "Content-Type: application/json" \
    -d '{
        "quoteId": "Q123",
        "invoiceId": "INV123",
        "expiry": 3600,
        "description": "Mint 1000 sats",
        "request": "lnbc10n1p...",
        "amount": 1000,
        "unit": "sat"
    }')

QUOTE_ID=$(echo $QUOTE | jq -r '.quoteId')
INVOICE=$(echo $QUOTE | jq -r '.request')
```

### 2. Display Invoice to User

The `request` field contains the BOLT11 invoice that the user must pay.

### 3. Poll for Payment Status

```bash
# Poll until paid
while true; do
    PAYMENT=$(curl -s "http://localhost:8080/payment/search/findByQuoteId?quoteId=$QUOTE_ID")
    STATE=$(echo $PAYMENT | jq -r '.state')

    if [ "$STATE" = "PAID" ] || [ "$STATE" = "CONFIRMED" ]; then
        echo "Payment received!"
        break
    fi

    echo "Waiting for payment... (state: $STATE)"
    sleep 2
done
```

### 4. Proceed with Minting

Once the payment state is `PAID` or `CONFIRMED`, proceed with token minting.

## Using with MockLnServer

When testing with `phoenixd-mock` and `PHOENIXD_AUTOPAY_ENABLED=false`:

### Simulate Payment via /mockpay

```bash
# Get the payment hash from the payment record
PAYMENT_HASH=$(curl -s "http://localhost:8080/payment/search/findByQuoteId?quoteId=$QUOTE_ID" \
    | jq -r '.paymentHash')

# Simulate payment received
curl -X POST "http://localhost:9740/mockpay?paymentHash=$PAYMENT_HASH"
```

This triggers:
1. MockLnServer marks invoice as settled
2. Webhook sent to payment-gateway
3. Payment state updated to `PAID`

## Error Handling

### Quote Expired

If polling exceeds the quote expiry time, the quote and associated payment should be considered invalid. Request a new quote.

### Payment Not Found

If `findByQuoteId` returns 404, the payment record may not have been created yet. This can happen if there's a race condition between quote creation and payment record creation.

### Network Errors

Implement retry logic for transient network failures during polling.

## Related Documentation

- [REST API Reference](api.md) - Complete endpoint documentation
- [Webhook Reference](webhook.md) - Webhook processing details
- [phoenixd-mock README](https://github.com/tcheeric/phoenixd-java/tree/develop/phoenixd-mock) - Mock server configuration