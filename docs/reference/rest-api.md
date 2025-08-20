# REST API

The REST service exposes endpoints for managing quotes and payments. Base URL defaults to `http://localhost:8080`.

## Quote Endpoints

- `GET /quote` – list quotes
- `POST /quote` – create a quote
- `GET /quote/{id}` – fetch a quote by numeric id
- `GET /quote/search/findByQuoteId?quoteId=...` – fetch by external quote id
- `GET /quote/search/findByInvoiceId?invoiceId=...` – fetch by Lightning invoice id

## Payment Endpoints

- `GET /payment` – list payments
- `POST /payment` – create a payment
- `GET /payment/{id}` – fetch a payment by numeric id
- `GET /payment/search/findByPaymentId?paymentId=...`
- `GET /payment/search/findByQuoteId?quoteId=...`
