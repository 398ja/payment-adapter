# Changelog

## [0.16.1] - 2026-09-22

### Security

- **BouncyCastle 1.84 -> 1.85 for CVE-2026-8763 (CRITICAL)**, via `imani-bom` 0.1.98. X.509
  Name Constraints can be bypassed with a trailing dot in an `rfc822Name` or URI, so a
  certificate can assert a name the constraint exists to forbid.

- **A real-looking credential removed from test resources.** `phoenixd.password` held a
  64-hex value here and in cashu-mint's history via a since-deleted copy of the same file.
  Staging runs `phoenixd-mock`, so there is most likely nothing behind it — and "most likely"
  is the problem, because neither a reader nor a scanner can tell a dead credential from a
  live one.

  Replaced with an obvious placeholder rather than allowlisted: allowlisting would teach the
  scanner to ignore exactly the shape a real leak takes. The value remains in git history in
  both repositories; if phoenixd is ever pointed at a real node, this password must not be
  the one used.

### Fixed

- **Quietening the absent-row case had also quietened failed writes.** 0.16.0 stopped every
  webhook logging a stack trace for an empty `payment` table, which was right — three of
  those had to be ruled out before the real defect in cashu-mint#462 could be seen — but the
  `NotFound` catch covered both the lookup *and* the update.

  So a 404 from the **write** — a row deleted between the read and the write, or a client
  pointed at a stale path — was logged at debug and discarded, making a lost state transition
  indistinguishable from a row that was never there. A failed read means there is nothing to
  do; a failed write means a payment state transition was dropped.

  Now split: `NotFound` on the lookup returns quietly, anything else on the lookup is an
  error, and the write has its own catch that always logs at ERROR with the state it was
  trying to set.

### Changed

- `imani-bom` 0.1.81 -> 0.1.98.

## [0.16.0] - 2026-09-22

Settled payments the mint was never told about (398ja/cashu-mint#462).

### Fixed

- **A forward to the mint could fail and leave no trace.** `HttpMintWebhookForwarder`
  retries three times and then returns `false`; `PhoenixWebhookHandler.forwardToMint`
  discarded that boolean. A payment the mint never heard about looked exactly like one it
  did — the quote sat `PAID`, the customer's money was taken, and the only record was a
  log line.

  Measured on staging: 240 quotes `PAID`, 152 `accepted` webhook events in the mint, and
  **7 quotes the mint has a row for and no webhook at all** — 460 sat, oldest three weeks.
  One of them is a `mint_quote` still reading `UNPAID` while the adapter holds the money.

- **No `@Scheduled` method in this application ever ran.** `@EnableScheduling` was declared
  only on `CashGatewayConfig`, an unrelated config in another module, so scheduling here
  existed as a side effect of that bean happening to load. It is now on the application.

  The failure is the quiet kind: a `@Scheduled` bean still constructs and still registers
  its gauge, so `payment_adapter_paid_unforwarded` published a confident `0.0` while the
  database held 88. A missing series would have been noticed; a zero reads as "no money
  stranded" and is strictly worse than publishing nothing.

- **A missing `payment` row is no longer logged as an error.** Spring Data REST answers 404
  for an empty `Optional`, so `getByPaymentId` throws rather than returning null. On a
  deployment that does not populate that table — staging has zero rows — every webhook
  logged a stack trace at ERROR for a normal condition. Three fired during this
  investigation and had to be ruled out before the real defect was visible.

- **The sweep's settings had no property bindings**, only `@Value` defaults, so an
  operator could not enable it without changing code. A flag that cannot be reached from
  the environment is not a flag. `mint.webhook.reconcile.*` now binds
  `MINT_WEBHOOK_RECONCILE_*`, and still defaults to off.

### Added

- **`quote.mint_notified_at`** (`V11`). NULL on a `PAID` quote now means precisely "money
  taken, mint not told". Nothing on either side recorded the forward before, so nothing
  could reconcile it.
- **`PaidQuoteForwardReconciler`** — a sweep that re-delivers settled payments past a grace
  period. Safe to retry: the mint's webhook is idempotent on
  `(provider, provider_event_id)`, so a payment that did arrive is classified `duplicate`
  and nothing is double-funded.
- **`payment_adapter_paid_unforwarded`** — the gauge has to live here, because the adapter
  is the only side that knows a payment happened. A mint-side gauge cannot express this,
  which is exactly why cashu-mint#459's gauge reads zero while these sit stranded.

### Notes for operators

- **The sweep is disabled by default** (`mint.webhook.reconcile.enabled=false`).
  Re-delivering payments taken weeks ago mints value against them, including one whose mint
  quote still reads `UNPAID`. That is an operator's decision, not a side effect of deploying.
- **Run the cross-service back-fill before enabling it, or before trusting the gauge.**
  Historical rows are deliberately left NULL, so on an existing deployment the gauge reads
  the whole `PAID` backlog rather than the stranded part of it. The honest back-fill stamps
  `mint_notified_at` for each `PAID` quote that has an `accepted` row in
  `cashu_mint.webhook_event`. A migration here cannot see the mint's tables, so it is a
  one-off operator step across two databases.

## [0.15.0] - 2026-09-09

Card purchases: a shopper buys a coupon from a stall, paying the stall directly.

### Added
- **Coupon purchases as direct charges on the stall's connected account.** The
  platform key signs and `Stripe-Account` decides whose money it is, so the stall
  is merchant of record and Imani never holds the funds. No stall hands over a
  Stripe key (ADR).
- **A purchase API that refuses before the card form.** `POST /api/v1/checkout/purchase`
  answers "this stall does not take card payments", "cannot accept card payments yet"
  or "has no currency configured" rather than letting a customer discover it at Stripe.
  Public and unauthenticated, so it carries an amount ceiling.
- **A paid purchase is recorded as a debt the issuer works off**, and discharged only
  when the gateway confirms issuance. A failure leaves a recoverable debt rather than
  a lost sale.
- **The card flows run against `stripe-mock`** with no Stripe account at all, behind a
  boot guard that refuses any api-base not naming a known local mock.

### Fixed
- **Connected accounts now ask Stripe for `card_payments`.** Accounts were created with
  a type and a country and no capabilities, and Stripe requests none by default. Express
  grants `transfers` on its own, and transfers ALONE set `charges_enabled` — so an
  account reporting `charges_enabled: true`, `payouts_enabled: true`,
  `details_submitted: true` and no requirements due could not accept a card. Every
  signal said ready. The refusal arrived as a 400 inside Stripe's hosted checkout,
  after a buyer had typed their card, with nothing logged here because nothing here
  was called.
- **`chargesEnabled` now means "can take a card today".** Narrowed to require an active
  `card_payments` capability, in BOTH snapshot paths — the API client and the
  `account.updated` webhook, the latter of which writes straight to the database and
  would otherwise have restored the old lie on the event most likely to follow
  onboarding. The rule lives once, in `CardChargeCapability`, and a test fails the
  build if any other production file reads `charges_enabled` raw.
- **The service refuses to start with Connect on and the gateway off.** Only
  `stripe.connect.enabled` set meant a stall could connect, publish `acceptsCards`, and
  every shopper hit a controller that was never registered. A 404 reached a customer
  and the stall had no way to see it.
- **A stored account id the current key cannot use is recovered from.** Real Stripe
  answers 403 `account_invalid`, not 404, so the existing recovery never ran and
  switching keys left every stall permanently broken with no route forward from the UI.
- **Two ways past the purchase API's only guard are closed.** The filter read the raw
  request URI and was registered on the protected prefix; both were weaker than they
  looked.

## [0.14.2] - 2026-08-29

### Fixed
- **A missing payment record is a 404, not a null.** 0.14.1 routed RECEIVE quotes past the
  payment lookup with a null check, but `PaymentClient.getByQuoteId` uses
  `RestTemplate.getForEntity`, which throws `HttpClientErrorException.NotFound`. The null branch
  was therefore unreachable and the webhook still failed. Now caught. The test stubs the throw
  rather than a null, which is what the real client does.

## [0.14.1] - 2026-08-29

### Fixed
- **Money arriving into a RECEIVE quote is now reported to the mint.** `PhoenixWebhookHandler`
  required a `GatewayPayment` row, but that is only created by `PhoenixdGateway.pay()` when the
  gateway pays an invoice OUT. A mint quote is the opposite direction and has no payment row by
  design, so every incoming-payment webhook died on "Payment not found" while the quote sat
  `PAID` — the mint never recorded funding and issuance failed with `funding_required`. For a
  RECEIVE quote the quote itself is now validated and forwarded.

## [0.14.0] - 2026-08-29

### Fixed
- **The phoenixd webhook path is on the app's classpath again.** `payment-adapter-rest`
  depended only on the Stripe modules, so `PhoenixWebhookHandler` and
  `MintWebhookForwarder` were not in the image; `@WebServlet("/webhook/phoenixd")` had no
  `@ServletComponentScan` to register it; and `WebhookRegistry` builds handlers through
  `ServiceLoader`, which can only use a no-arg constructor, so the registered handler's
  `mintForwarder` was always null and the forward was skipped silently. Payments therefore
  never reached the mint and voucher issuance failed with `funding_required`.
- Removed a cyclic dependency: `payment-adapter-ln-phoenixd` declared a dependency on
  `payment-adapter-rest` that it never imported.

### Changed
- phoenixd-java 0.2.0 -> 0.3.0, phoenixd-mock 0.1.4 -> 0.3.0.

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Inherits dependency versions from `imani-bom` 0.1.77 (was 0.1.76), which carries the
  2026-09-13 AppSec remediation: `imani-security` 0.2.0, `imani-gateway-api` 0.8.0 and
  wallet-lib 0.2.0. See `imani-docs/security/appsec-2026-09-13/` for the register of findings.

### Fixed

- `/actuator/prometheus` no longer returns 404. The Prometheus registry has been
  on the classpath all along, but `prometheus` was missing from
  `management.endpoints.web.exposure.include`, so every metric this service
  recorded was unreachable. Nothing failed, because a service whose metrics
  cannot be scraped looks exactly like a service that is idle. Confirmed against
  the running staging container before and after.

### Added

- `ActuatorPrometheusExposureTest`, which both boots the app and asserts the
  endpoint serves exposition text, and reads the *shipped* configuration from the
  source tree. Both halves are needed: `src/test/resources/application.properties`
  shadows the shipped file, so the boot test alone would only prove the test
  config. Confirmed to fail on the original 404. `ActuatorSharedPortExposureTest`
  additionally boots with the ports shared, the way production runs, which is
  the arrangement a separate-port test cannot exercise.
- Common metric tags `application=payment-adapter` and `env`, matching the labels
  the mint applies, so one Grafana dashboard can filter across both services.

### Changed

- `management.server.port` is now configurable via
  `PAYMENT_ADAPTER_MANAGEMENT_PORT`, defaulting to the API port so existing
  container healthchecks and probes keep working. Operators should split it in
  any externally reachable environment, since `/actuator/prometheus` exposes
  payment volumes and gateway failure counts. `management.server.address` is
  deliberately not set: Spring Boot refuses to start when it is present while
  the management and server ports are the same, which is the default.
- Updated cashu-lib to 0.21.0 (NUT-11 P2PK secret validation). Validation is fail-closed:
  a malformed P2PK lock is now rejected at parse time rather than accepted and misbehaving later.

## [0.13.2] - 2026-06-06

### Fixed
- **Stripe refunds recorded the original charge amount, not the refunded total.** `handleChargeRefunded` set `refundedAmountMinor` from the charge `amount`; it now parses Stripe's `amount_refunded` field (via a new `StripeWebhookPayload.refundedAmountMinor`), so partial refunds record the actual refunded value. [PR #229 review]

### Documentation
- README: `stripe.default-currency` is a currency code (e.g. `usd`), not a minor-units amount. [PR #229 review]
- `docs/reference/configuration.md`: webhook tolerance is read from the `STRIPE_WEBHOOK_TOLERANCE_SECONDS` environment variable (the verifier is instantiated via `ServiceLoader`, outside Spring), not a Spring property. [PR #229 review]

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
