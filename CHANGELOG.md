# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.13.1] - 2026-06-06

### Fixed
- **V6 Flyway migration crashed on fresh databases.** `V6__add_quote_created_at.sql` did `ALTER TABLE quote …`, but `quote` is created by Hibernate `ddl-auto`, not by an earlier migration — Flyway runs first, so on a fresh DB (CI/tests, clean deploy) the table didn't exist yet (`Table "QUOTE" not found`). Now guarded with `ALTER TABLE IF EXISTS` (H2 2.x + PostgreSQL), making it a safe no-op there while still adding the column on environments where `quote` predates it.
- **Stripe webhooks always failed (`StripeWebhookHandler` had null repositories).** `WebhookRegistry` discovers handlers via `ServiceLoader`, so Spring never invoked the `@Autowired` setters and every Stripe payment webhook failed `ensureDependencies()`. Added `StripeWebhookHandlerConfiguration` which registers a Spring-wired handler, replacing the ServiceLoader stub.
- **Stripe/card quotes skipped strict expiry enforcement.** `StripeGateway` persisted `created_at` but didn't override `Gateway.getCreatedAt()`, so callers fell back to `null` and never enforced `createdAt + expiry`. Added the override (mirrors `PhoenixdGateway`).
- **First-time failing Stripe Connect webhooks left no audit record.** The `@Transactional` `handleWebhook` rolled back the just-inserted PROCESSING row, and the `REQUIRES_NEW` failure handler only did `findById` (couldn't see the uncommitted row), so no FAILED record persisted. `markFailed` now upserts — creating the FAILED record in the new transaction when absent.

## [0.13.0] - 2026-06-05

### Added
- `Gateway.getCreatedAt(quoteId)` default port returning `Instant` (default: `null`). Lets cashu-mint's `MintTask` enforce strict NUT-04 quote expiry by computing `createdAt + getPaymentExpiry()` — required by spec 041 REQ-MINT-3 (client-side voucher minting).
- `GatewayQuote.createdAt` JPA column (`@Column(name = "created_at") Instant`) auto-populated by a `@PrePersist` hook. Nullable for backward compatibility — pre-existing rows decode cleanly and the mint falls through to permissive behaviour for those.
- `PhoenixdGateway.getCreatedAt(String)` override — looks up the JPA row and returns `createdAt`.

### Changed
- None of the additions are breaking. The new interface default method preserves binary compatibility for existing `Gateway` implementations; the new JPA column is additive.

## [0.12.0] - 2026-03-22

### Added
- Stripe Connect onboarding flow with `createOrResume`, `refresh`, `getStatus`, and `disconnect` endpoints
- `StripeConnectClient` interface and `StripeSdkConnectClient` implementation for Stripe Account API
- `StripeConnectController` REST endpoints for merchant account lifecycle management
- `StripeConnectExceptionHandler` with structured error responses and exception codes
- `StripeAccountSnapshot` record for mapping Stripe Account API responses
- `StripeConnectConfig` for Stripe Connect bean configuration
- Webhook handling for `account.updated` and `account.application.deauthorized` events with idempotent processing
- `details_submitted`, `requirements_due`, `disabled_reason`, `country`, and `email` fields on `ConnectedStripeAccount` entity
- Flyway V5 migration to extend `connected_stripe_account` table with new columns
- Integration tests for `StripeConnectService` with mocked Stripe client

### Fixed
- V5 Flyway migration H2 compatibility by splitting multi-column `ALTER TABLE` into individual statements
- Stripe settlement gated on `payment_status` for async payment methods
- `paymentStatus` assertion added to amount-mismatch integration test

## [0.11.0] - 2026-03-21

### Added
- `payment-adapter-stripe` aggregator with gateway, webhook, and connect modules
- `StripeGateway` with hosted Checkout Session quote creation and persisted pending payment records
- Stripe persistence entities and Flyway migration for payment references, processed webhook events, and connected accounts
- `StripeWebhookHandler` with signature verification, duplicate-event tracking, and quote/payment reconciliation
- Stripe configuration properties, documentation, and tests for gateway, webhook, and connect flows
- `QuoteClient.updateQuote()` method for PUT requests on quote entities

### Fixed
- `@Autowired` annotation on `CashWebhookHandler` repository setter
- Dockerfiles and compose configuration for new module structure
- Invalid customer pubkey handling in cash receipt flow

## [0.10.0] - 2026-02-18

### Added
- `customerPubkey` field on `CashInvoice` entity and `PaymentNotification` DTO
- `POST /cash/invoice/{ref}/intent` endpoint on `CashPaymentController`
- `customerPubkey` field on `CashInvoiceResponse` DTO
- `unit` field on `PaymentNotification` for currency denomination

### Fixed
- Maven profile defaults inverted so `-P integration-tests,e2e-tests` activates both suites correctly
- Test compilation for `GatewayWebhookForwarder` dependency across integration and E2E modules
- `FlywayMigrationIT` updated for `customer_pubkey` column expectation
- Surefire/Failsafe plugin configuration in `payment-adapter-cash-gateway` to respect profile-based test skipping

## [0.9.0] - 2026-02-18

### Added
- `payment-adapter-test` aggregator module with Testcontainers PostgreSQL
  - `payment-adapter-test-integration`: 61 integration tests (repositories, services, migrations, webhooks, rate limiter)
  - `payment-adapter-test-e2e`: 50 end-to-end tests (REST flows, QR codes, SSE events, concurrency, error handling)
- Maven profiles for selective test execution (`-P integration-tests`, `-P e2e-tests`)
- `CashInvoiceService` for cash invoice business logic
- `CashRateLimiter` with token bucket rate limiting for invoice creation
- `NostrClient` and `WebSocketRelayConnection` for Nostr relay communication
- `Nip44EncryptionService` for NIP-44 payload encryption
- `EphemeralKeyPair` for per-invoice cryptographic key generation
- `CashPayloadCodec` and `NfcNdefBuilder` for cash payment URI encoding
- `NostrEventBase` for Nostr event construction and signing
- `CashEventFilter` for filtering cash-related Nostr events
- `CashReceiptStatus` enum for receipt state tracking
- `CashInvoiceRepository`, `CashIntentRepository`, `CashReceiptRepository` Spring Data interfaces
- Flyway migrations V1-V3 for cash invoice, intent, and receipt tables
- SSE endpoint `GET /cash/invoice/{ref}/events` for real-time status updates
- QR payload endpoint `GET /cash/invoice/{ref}/qr-payload` for URI content
- Unit tests for CashGateway, CashInvoiceStateMachine, NostrClient, EphemeralKeyPair, Nip44EncryptionService, CashWebhookHandler, and Nostr codec

### Changed
- Updated `cashu-lib.version` to 0.16.0
- Updated `nostr-java.version` to 1.2.1
- Refactored `CashPaymentController` to use `CashInvoiceService`
- Refactored `CashEventSubscriber` with relay lifecycle management and scheduled expiry checks
- Refactored `CashWebhookHandler` with validation and duplicate detection

## [0.8.0] - 2026-02-02

### Added
- `MintWebhookForwarder` interface for push-based payment notifications
- `HttpMintWebhookForwarder` implementation with retry logic and exponential backoff
- `PaymentNotification` DTO for forwarding payment confirmations to cashu-mint
- HMAC signature authentication for webhook forwarding
- Configuration properties `mint.webhook.url` and `mint.webhook.secret`
- Added log directory entries to `.gitignore` for payment adapter modules.

### Changed
- Updated `PhoenixWebhookHandler` to forward payments to mint via `MintWebhookForwarder`
- This enables real-time payment notifications to cashu-mint instead of polling
- Updated `nostr-java.version` to 1.2.1.
- Updated `cashu-lib.version` to 0.16.0.
- Moved `PaymentMethod` imports to the NUT-18 package.

### Removed
- Removed committed log files from the repository.

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
