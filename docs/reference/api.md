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

## Stripe Connect: connect a stall's existing Stripe account

For a farm that already has a Stripe account. Both calls are server to server, like the rest of
`/api/v1/stripe/connect`. Needs `stripe.connect.oauth.client-id` and `stripe.connect.oauth.redirect-uri`
([configuration](configuration.md)); otherwise both answer `503 OAUTH_DISABLED`.

### Start

`POST /api/v1/stripe/connect/oauth/start`

```json
{ "merchant_pubkey": "npub1..." }
```

Returns `{ "authorize_url": "https://connect.stripe.com/oauth/authorize?...", "expires_at": "..." }`. Send the farm's
browser to `authorize_url`. A merchant that already has a connected account gets `409 MERCHANT_ALREADY_LINKED`.

### Complete

`POST /api/v1/stripe/connect/oauth/complete`

```json
{ "code": "ac_...", "state": "..." }
```

Call this with the `code` and `state` Stripe appended to the redirect URI. The merchant is read from the stored
`state`, never from this body. The state works once: a second call with it, an expired one or an unknown one answers
`400 OAUTH_STATE_INVALID` and no code is sent to Stripe. The authorization code is exchanged exactly once, with no
retries, because Stripe revokes the connection if a code is used twice. Success returns the same account body as
`POST /api/v1/stripe/connect/accounts`. The account is stored even if it cannot take cards yet; check
`charges_enabled`.

| Status | Code | Meaning |
|---|---|---|
| 400 | `OAUTH_STATE_INVALID` | Unknown, expired or already-used state, or missing input. Start again. |
| 400 | `OAUTH_CODE_REJECTED` | Stripe refused the code (expired, used, or not ours). Start again. |
| 409 | `MERCHANT_ALREADY_LINKED` | The Stripe account belongs to another merchant, or this merchant already has a different one. |
| 409 | `OWNERSHIP_MISMATCH` | The Stripe account carries another merchant's key. |
| 502 | `STRIPE_API_ERROR` | Stripe could not be reached or failed. The state is spent, so start again. |
| 503 | `OAUTH_DISABLED` | OAuth is not configured. |

## Instalment plans

Needs `stripe.recurring.enabled=true` ([configuration](configuration.md)). A plan is a Stripe subscription on the
STALL's own account; Imani issues vouchers for each instalment when, and only when, that instalment's invoice is paid.

### Start a plan (public, like a purchase)

`POST /api/v1/checkout/plan`

```json
{ "stall_pubkey": "<64 hex>", "buyer_pubkey": "<64 hex>", "amount_minor": 5000, "instalments": 6, "interval": "month" }
```

`interval` is `week`, `month` or `year`, and defaults to `month` when left out. The stall must have switched plans on
for that interval (see [A stall's offer](#a-stalls-offer-signed-by-the-stall)); otherwise `409` with `code`
`NOT_OFFERED`, or `INTERVAL_NOT_OFFERED` with the stall's `intervals`. The longest fixed term depends on the interval
(`max-instalments`, `-weekly`, `-yearly` in [configuration](configuration.md)).

`instalments` is 2 to `stripe.recurring.max-instalments`, or omitted for an open-ended plan. Returns
`{ "checkout_url": "https://checkout.stripe.com/...", "plan_id": "..." }`: send the buyer to `checkout_url`. The buyer
always returns to the deployment's own purchase pages; a caller cannot choose the return URL.

| Status | Meaning |
|---|---|
| 400 | Missing or invalid input, an instalment below the minimum, or a term out of range. |
| 404 | The stall does not take card payments. |
| 409 | The stall cannot take cards yet, has no currency, is not ready or over its sales limit, or its Stripe account is not set up for plans (Imani would bear fees or losses). |
| 422 | `buyer_pubkey` is not a wallet key: a plan cannot be delivered by claim link. |
| 503 | Stripe or the readiness check is unavailable; retry after the `Retry-After` header. |

### Read and cancel a plan (signed by the buyer, from the wallet)

Every route below is a **signed request**: `Authorization: Nostr <base64 NIP-98 event>` for the exact URL and verb,
against one of `purchase.state.public-origins`. The buyer is whoever signed; no key is taken from the query or body.
`401` without a valid signature, `503` when no public origin is configured. A plan that belongs to someone else answers
`404`, the same as an unknown one. Answers are camelCase.

* `GET /api/v1/checkout/plans` lists the signer's plans, newest first (at most 100): `[{planId, stallPubkey, status,
  amountMinor, currency, totalInstalments, instalmentsPaid, cancelRequested, cancelRequestedAt, createdAt}]`.
* `GET /api/v1/checkout/plans/{planId}` returns `{plan: {...as above}, voucherIds: [...]}`, the vouchers its
  instalments issued, oldest first.
* `POST /api/v1/checkout/plans/{planId}/cancel` (no body) ends the subscription at period end on the stall's account.
  Vouchers already issued stay valid and nothing is refunded (a refund is the stall's decision, made in its own Stripe
  account). Repeating it on a plan already stopping changes nothing. A `PENDING` plan (Stripe's page never finished)
  has its open Checkout Session expired first, so the buyer cannot finish it afterwards, and is then closed. `409` if
  the plan already ended or is `ORPHANED`; `502` if Stripe cannot be reached, or the pending page has just been
  completed (the plan is left as it was).
* `GET /api/v1/checkout/plans/summary?stall=<pubkey>`, signed BY that stall (`403` otherwise): `{activePlans,
  monthlyTotals: [{currency, amountMinor}]}` over `ACTIVE` plans. No buyers.

The plan start (`POST /api/v1/checkout/plan`) answers `{checkoutUrl, planId}`. Its refusals carry a `code` beside
`error` where the wallet words them itself: `BELOW_MINIMUM` (with `minAmountMinor`), `NOT_CONNECTED`,
`ACCOUNT_NOT_ELIGIBLE`, `NOT_OFFERED`, `INTERVAL_NOT_OFFERED` (with `intervals`).

Each plan in a list or read also carries `interval`. The stall summary keeps `monthlyTotals` for monthly plans only,
and adds `totals: [{currency, interval, amountMinor}]` across every interval, since a weekly and a monthly amount
cannot be summed.

### A stall's offer (signed by the stall)

Whether a stall offers instalment plans, and how often a buyer may pay, is decided HERE, not by the stall's public
profile: plan start is public, so a flag only the wallet reads could be bypassed by calling it directly.

* `GET /api/v1/checkout/plans/settings?stall=<pubkey>` (public): `{enabled, intervals}`. A stall that never saved one
  answers `{enabled: false, intervals: []}`.
* `POST /api/v1/checkout/plans/settings`, a **signed request** BY the stall, whose NIP-98 event also carries the
  `payload` tag (sha256 of the exact body), so a captured signature cannot be replayed with other settings. Body
  `{enabled: true|false, intervals: ["week"|"month"|"year", ...]}`. Leaving `intervals` out keeps the stall's
  current ones (`["month"]` for a new stall), so a client that only knows monthly cannot erase other intervals. The stall
  is whoever signed; a body that names another `stallPubkey` is `403`. Switching off stops new plans; plans already
  running carry on. `400` for anything else, `401` unsigned, `503` with no public origin configured.

The Connect status (`GET /api/v1/stripe/connect/accounts/{merchantPubkey}`) also carries `connection_type`
(`CREATED` | `OAUTH`), `fee_payer`, `losses_payer`, `instalments_eligible` (strict: unknown is false) and
`active_plans` (a plan is charging, so disconnecting is refused). Disconnecting an `OAUTH` account also calls Stripe's
`oauth/deauthorize` after the plan guard, best effort.

Statuses: `PENDING`, `ACTIVE`, `PAST_DUE`, `COMPLETED`, `CANCELLED`, `ORPHANED` (the stall disconnected from Imani; Stripe
keeps charging), `ATTENTION` (charged after the plan ended or past its term; a person decides about a refund).

## Error codes

The API uses standard HTTP status codes:

* `201 Created` – resource created successfully.
* `204 No Content` – operation completed (cancel).
* `400 Bad Request` – missing or malformed data.
* `404 Not Found` – resource does not exist.
* `409 Conflict` – invalid state transition (e.g., confirming an expired invoice).
* `500 Internal Server Error` – unexpected server error.

Error responses follow the default [Spring Boot error format](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#web.error-handling).
