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

## Error codes

The API uses standard HTTP status codes:

* `201 Created` – resource created successfully.
* `400 Bad Request` – missing or malformed data.
* `404 Not Found` – resource does not exist.
* `500 Internal Server Error` – unexpected server error.

Error responses follow the default [Spring Boot error format](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#web.error-handling).

