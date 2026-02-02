# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Added log directory entries to `.gitignore` for payment adapter modules.

### Changed
- Updated `nostr-java.version` to 1.2.1.
- Updated `cashu-lib.version` to 0.16.0.
- Moved `PaymentMethod` imports to the NUT-18 package.

### Removed
- Removed committed log files from the repository.

## [0.8.0] - 2026-01-25

### Added
- `MintWebhookForwarder` interface for push-based payment notifications
- `HttpMintWebhookForwarder` implementation with retry logic and exponential backoff
- `PaymentNotification` DTO for forwarding payment confirmations to cashu-mint
- HMAC signature authentication for webhook forwarding
- Configuration properties `mint.webhook.url` and `mint.webhook.secret`

### Changed
- Updated `PhoenixWebhookHandler` to forward payments to mint via `MintWebhookForwarder`
- This enables real-time payment notifications to cashu-mint instead of polling

## [0.7.0] - 2026-01-25

### Added
- New `payment-adapter-cash` module for NIP-XX Cash Payments over Nostr
  - `payment-adapter-cash-nostr`: Nostr event types (kinds 5200-5204), URI codec
  - `payment-adapter-cash-gateway`: CashGateway implementation, QR code generation
  - `payment-adapter-cash-webhook`: CashWebhookHandler for processing intents
- CashInvoice, CashIntent, CashReceipt JPA entities in payment-adapter-model
- CashInvoiceStatus enum for state machine transitions
- REST API endpoints for cash payments:
  - `POST /cash/invoice` - Create new cash invoice
  - `GET /cash/invoice/{ref}` - Get invoice status
  - `POST /cash/invoice/{ref}/confirm` - Confirm cash received
  - `POST /cash/invoice/{ref}/cancel` - Cancel invoice
  - `GET /cash/invoice/{ref}/qr` - Get QR code as PNG
- CashInvoiceStateMachine for state transitions
- CashEventSubscriber for relay monitoring
- NostrCashUri codec for `nostr+cash://` URI scheme
- QRCodeGenerator using ZXing library
- nostr-java 1.2.0 integration for Nostr protocol operations

### Changed
- Restructured project to support multiple payment types
- Renamed payment-adapter-phoenixd module to payment-adapter-ln
- Added payment-adapter-ln-webhook for Lightning webhook handling
- Added payment-adapter-ln-dummy for testing
- Renamed project from payment-gateway to payment-adapter

## [0.5.0] - 2026-01-20

### Changed
- Renamed project from payment-gateway to payment-adapter (all module artifactIds and documentation updated)
- Added `/payment/search/findByQuoteId` endpoint to return payments by quote id using `GatewayPayment`.
- Improved webhook request validation: clarified missing `externalId` error and validation for numeric `amountSat`.
- Hardened logging to filter sensitive property keys more precisely and avoid leaking secrets.
- Configured Dependabot for Maven updates.

## [0.4.9] - 2026-01-10
### Changed
- Bumped project version to 0.4.9.
- Removed the `nostr-java-bom` import and now depend directly on `xyz.tcheeric:nostr-java` 1.2.0.
