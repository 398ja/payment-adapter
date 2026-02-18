# REST API Reference

This document describes the HTTP endpoints exposed by the Payment Adapter REST service.

## Quote endpoints

### List quotes

`GET /quote`

Optional query parameters: `page`, `size`, `sort`.

Example request:

```bash
curl http://localhost:8080/quote
```

Example response:

```json
{
    "_embedded": {
        "quotes": [
            {
                "id": 1,
                "quoteId": "Q123",
                "invoiceId": "INV1",
                "expiry": 3600,
                "description": "Example quote",
                "request": "lnbc1...",
                "amount": 1000,
                "unit": "sats",
                "state": "PENDING",
                "direction": "RECEIVE"
            }
        ]
    },
    "page": {
        "size": 20,
        "totalElements": 1,
        "totalPages": 1,
        "number": 0
    }
}
```

### Create quote

`POST /quote`

Required JSON body fields: `quoteId`, `invoiceId`, `expiry`, `description`, `request`, `amount`, `unit`.

Example request:

```bash
curl -X POST http://localhost:8080/quote \
    -H "Content-Type: application/json" \
    -d '{
        "quoteId": "Q123",
        "invoiceId": "INV1",
        "expiry": 3600,
        "description": "Example quote",
        "request": "lnbc1...",
        "amount": 1000,
        "unit": "sats"
    }'
```

Example response (201 Created):

```json
{
    "id": 1,
    "quoteId": "Q123",
    "invoiceId": "INV1",
    "expiry": 3600,
    "description": "Example quote",
    "request": "lnbc1...",
    "amount": 1000,
    "unit": "sats",
    "state": "PENDING",
    "direction": "RECEIVE"
}
```

### Get quote by id

`GET /quote/{id}`

Example request:

```bash
curl http://localhost:8080/quote/1
```

### Find quote by quoteId

`GET /quote/search/findByQuoteId?quoteId=<quote-id>`

Required query parameter: `quoteId`.

Example request:

```bash
curl "http://localhost:8080/quote/search/findByQuoteId?quoteId=Q123"
```

### Find quote by invoiceId

`GET /quote/search/findByInvoiceId?invoiceId=<invoice-id>`

Required query parameter: `invoiceId`.

Example request:

```bash
curl "http://localhost:8080/quote/search/findByInvoiceId?invoiceId=INV1"
```

## Payment endpoints

### List payments

`GET /payment`

Optional query parameters: `page`, `size`, `sort`.

Example request:

```bash
curl http://localhost:8080/payment
```

### Create payment

`POST /payment`

Required JSON body fields: `request`, `paymentId`, `quoteId`, `sourceCurrency`, `amount`, `lightningNetworkFee`, `totalAmount`, `paymentHash`, `paymentPreimage`.

Example request:

```bash
curl -X POST http://localhost:8080/payment \
    -H "Content-Type: application/json" \
    -d '{
        "request": "lnbc1...",
        "paymentId": "P123",
        "quoteId": "Q123",
        "sourceCurrency": "sats",
        "amount": 1000,
        "lightningNetworkFee": 10,
        "totalAmount": 1010,
        "paymentHash": "hash",
        "paymentPreimage": "preimage"
    }'
```

### Get payment by id

`GET /payment/{id}`

Example request:

```bash
curl http://localhost:8080/payment/1
```

### Find payment by paymentId

`GET /payment/search/findByPaymentId?paymentId=<payment-id>`

Required query parameter: `paymentId`.

Example request:

```bash
curl "http://localhost:8080/payment/search/findByPaymentId?paymentId=P123"
```

### Find payment by quoteId

`GET /payment/search/findByQuoteId?quoteId=<quote-id>`

Required query parameter: `quoteId`.

Example request:

```bash
curl "http://localhost:8080/payment/search/findByQuoteId?quoteId=Q123"
```

## Cash invoice endpoints

These endpoints manage NIP-XX Cash Payment invoices for in-person cash transactions over Nostr. See [Cash Payments](cash-payments.md) for protocol details.

### Create cash invoice

`POST /cash/invoice`

Creates a new cash invoice, generates an ephemeral keypair, publishes a kind 5200 event to Nostr relays, and returns a QR code.

**Request body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `amount` | integer | Yes | Amount in minor currency units (e.g., cents, satoshis). Must be ≥ 1. |
| `fiat` | string | No | ISO 4217 currency code (3 chars). Omit for satoshis. |
| `memo` | string | No | Description (max 256 chars). |
| `ttlSeconds` | integer | No | Invoice TTL in seconds (min 60, default 300). |
| `relayUrls` | string[] | No | Relay URLs (max 5). Defaults to configured relays. |

Example request:

```bash
curl -X POST http://localhost:8080/cash/invoice \
    -H "Content-Type: application/json" \
    -d '{
        "amount": 1500,
        "fiat": "USD",
        "memo": "espresso",
        "ttlSeconds": 300,
        "relayUrls": ["wss://relay.damus.io", "wss://nos.lol"]
    }'
```

Example response (201 Created):

```json
{
    "ref": "6f2c1d",
    "amount": 1500,
    "fiat": "USD",
    "memo": "espresso",
    "status": "PENDING",
    "merchantPubkey": "02a1b2c3d4e5f6...",
    "proofCode": "4821",
    "expiresAt": "2026-02-18T12:30:00Z",
    "relayUrls": ["wss://relay.damus.io", "wss://nos.lol"],
    "qrPayload": "nostr+cash://pay?k=02a1b2c3...&ref=6f2c1d&amt=1500&fiat=USD&exp=1739878200&r=wss%3A%2F%2Frelay.damus.io&r=wss%3A%2F%2Fnos.lol&enc=nip44&v=0.2",
    "qrDataUri": "data:image/png;base64,iVBORw0KGgo...",
    "createdAt": "2026-02-18T12:25:00Z",
    "publishedAt": "2026-02-18T12:25:00Z"
}
```

### Get cash invoice

`GET /cash/invoice/{ref}`

Returns the current status of a cash invoice.

Example request:

```bash
curl http://localhost:8080/cash/invoice/6f2c1d
```

Example response:

```json
{
    "ref": "6f2c1d",
    "amount": 1500,
    "fiat": "USD",
    "memo": "espresso",
    "status": "INTENT_RECEIVED",
    "merchantPubkey": "02a1b2c3d4e5f6...",
    "proofCode": "4821",
    "expiresAt": "2026-02-18T12:30:00Z",
    "relayUrls": ["wss://relay.damus.io", "wss://nos.lol"],
    "createdAt": "2026-02-18T12:25:00Z",
    "publishedAt": "2026-02-18T12:25:00Z",
    "intentReceivedAt": "2026-02-18T12:26:30Z"
}
```

Returns `404` if the invoice reference is not found.

### Confirm cash received

`POST /cash/invoice/{ref}/confirm`

Merchant confirms that physical cash was received. Transitions the invoice to `PAID` and publishes a kind 5202 CashReceipt event.

**Request body (optional):**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `amountReceived` | integer | No | Actual amount received (defaults to invoice amount). |
| `note` | string | No | Optional note. |

Example request:

```bash
curl -X POST http://localhost:8080/cash/invoice/6f2c1d/confirm \
    -H "Content-Type: application/json" \
    -d '{"amountReceived": 1500}'
```

Example response (200 OK):

```json
{
    "ref": "6f2c1d",
    "amountReceived": 1500,
    "eventId": "c3d4e5f6a1b2...",
    "confirmedAt": "2026-02-18T12:27:00Z"
}
```

Returns `404` if the invoice is not found. Returns `409 Conflict` if the invoice is in a state that cannot be confirmed (e.g., already `PAID`, `EXPIRED`, or `CANCELLED`).

### Cancel cash invoice

`POST /cash/invoice/{ref}/cancel`

Cancels a pending or intent-received invoice. Publishes a kind 5203 CashCancel event.

**Request body (optional):**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reason` | string | No | Reason code (e.g., `cash.timeout`, `cash.merchant_request`). Defaults to `cash.merchant_request`. |
| `note` | string | No | Optional note. |

Example request:

```bash
curl -X POST http://localhost:8080/cash/invoice/6f2c1d/cancel \
    -H "Content-Type: application/json" \
    -d '{"reason": "cash.timeout"}'
```

Returns `204 No Content` on success. Returns `404` if not found. Returns `409 Conflict` if the invoice is in a terminal state.

### Get QR code image

`GET /cash/invoice/{ref}/qr`

Returns the QR code as a PNG image.

Example request:

```bash
curl -o qr.png http://localhost:8080/cash/invoice/6f2c1d/qr
```

Returns `image/png` content type with `Cache-Control: max-age=300`. Returns `404` if the invoice is not found.

### Get QR payload

`GET /cash/invoice/{ref}/qr-payload`

Returns the raw `nostr+cash://` URI string for custom QR code generation.

Example request:

```bash
curl http://localhost:8080/cash/invoice/6f2c1d/qr-payload
```

Example response:

```
nostr+cash://pay?k=02a1b2c3...&ref=6f2c1d&amt=1500&fiat=USD&exp=1739878200&r=wss%3A%2F%2Frelay.damus.io&r=wss%3A%2F%2Fnos.lol&enc=nip44&v=0.2
```

## Error codes

The API uses standard HTTP status codes:

* `201 Created` – resource created successfully.
* `204 No Content` – operation completed (cancel).
* `400 Bad Request` – missing or malformed data.
* `404 Not Found` – resource does not exist.
* `409 Conflict` – invalid state transition (e.g., confirming an expired invoice).
* `500 Internal Server Error` – unexpected server error.

Error responses follow the default [Spring Boot error format](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#web.error-handling).
