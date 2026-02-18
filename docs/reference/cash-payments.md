# Cash Payments

Payment Adapter supports in-person cash transactions using the NIP-XX Cash Payments protocol over Nostr. Merchants publish short-lived invoices to Nostr relays; customers respond with payment intents; merchants confirm when physical cash is received.

See the full [NIP-XX specification](../../project/nostr-cash-payments-spec.md) for protocol details.

## Overview

Cash payments use Nostr event kinds 5200–5204:

| Kind | Name | Direction | Description |
|------|------|-----------|-------------|
| `5200` | CashInvoice | Merchant → relays | Invoice published and encoded as QR code |
| `5201` | CashIntent | Customer → Merchant | Customer signals intent to pay |
| `5202` | CashReceipt | Merchant → Customer | Confirmation that cash was received |
| `5203` | CashCancel | Either party | Cancellation or timeout |
| `5204` | CashDispute | Either party | Optional dispute record |

## Invoice Lifecycle

```
                ┌─────────────┐
                │   CREATED   │
                └──────┬──────┘
                       │ publish kind 5200
                       ▼
                ┌─────────────┐
        ┌───────│   PENDING   │───────┐
        │       └──────┬──────┘       │
        │ cancel       │ kind 5201    │ expiry
        ▼              ▼              ▼
 ┌───────────┐  ┌─────────────┐  ┌─────────┐
 │ CANCELLED │  │   INTENT    │  │ EXPIRED │
 └───────────┘  │  RECEIVED   │  └─────────┘
                └──────┬──────┘
                       │ confirmReceipt()
                       ▼
                ┌─────────────┐
                │    PAID     │ → publish kind 5202
                └─────────────┘
```

**States:**

| Status | Description | Terminal? |
|--------|-------------|-----------|
| `CREATED` | Invoice created, not yet published | No |
| `PENDING` | Published to relays, awaiting customer intent | No |
| `INTENT_RECEIVED` | Customer intent (kind 5201) received, awaiting cash | No |
| `PAID` | Cash confirmed, receipt (kind 5202) published | Yes |
| `EXPIRED` | Invoice TTL exceeded | Yes |
| `CANCELLED` | Cancelled by merchant or customer | Yes |

## `nostr+cash://` URI Scheme

Invoices are encoded as QR codes using the `nostr+cash://` URI scheme:

```
nostr+cash://pay?k=<merchant-pubkey>&ref=<nonce>&amt=<amount>&fiat=<code>&exp=<unix>&r=<relay>&enc=nip44&v=0.2
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `k` | Yes | Merchant ephemeral pubkey (hex) |
| `ref` | Yes | Invoice reference nonce (6–12 hex chars) |
| `amt` | Yes | Amount in minor currency units |
| `fiat` | Conditional | ISO 4217 currency code (omit for satoshis) |
| `exp` | Yes | Unix timestamp expiry |
| `r` | Yes (2+) | Relay URL (repeat for multiple) |
| `enc` | Recommended | Encryption mode (`nip44`) |
| `wrap` | Optional | Gift-wrap preference (`1` = true) |
| `h` | Optional | Hashed location token |
| `v` | Recommended | Protocol version |

## QR Code Generation

The `QRCodeGenerator` in the cash-gateway module generates QR codes from `nostr+cash://` URIs:

- Output formats: PNG bytes, Base64 string, `data:image/png;base64,...` data URI
- Configurable error correction level (default: M)
- Size optimization targeting < 300 bytes for scannability

The QR code is available via `GET /cash/invoice/{ref}/qr` (PNG image) or as a data URI in the `POST /cash/invoice` response.

## End-to-End Flow

1. **Merchant creates invoice** via `POST /cash/invoice` with amount, currency, and relay URLs
2. **Invoice published** to Nostr relays as kind 5200 event using an ephemeral keypair
3. **QR code displayed** to customer containing the `nostr+cash://` URI
4. **Customer scans QR**, generates their own ephemeral keypair, and sends kind 5201 CashIntent to relays
5. **Merchant receives intent** (via webhook at `POST /webhook/cash` or relay subscription)
6. **Cash exchange** happens physically; merchant optionally verifies proof code
7. **Merchant confirms** via `POST /cash/invoice/{ref}/confirm`; kind 5202 CashReceipt published
8. **Customer sees confirmation** on their device

## Configuration

See the [Configuration Reference](configuration.md#cash-gateway-module) for all cash-related properties.

## REST API

See the [API Reference](api.md#cash-invoice-endpoints) for endpoint details.
