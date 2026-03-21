# Payment Adapter - Stripe Module Spec

**Status:** Draft
**Date:** 2026-03-21
**Repository:** `payment-adapter`
**Document Type:** Specification

## Purpose

Define a Stripe-based payment adapter that lets the system accept hosted fiat
payments for Cashu voucher purchases without expanding PCI scope or weakening
the existing gateway model.

The Stripe module must align with the current `Gateway` contract, the existing
`GatewayQuote` and `GatewayPayment` entities, and the coding rules in
`CLAUDE.md`.

## Scope

### In Scope

- Hosted Stripe Checkout for one-time card payments.
- Wallets surfaced by Stripe Checkout and backed by card rails, such as Apple
  Pay and Google Pay.
- Webhook-driven payment confirmation.
- Optional Stripe Connect support for merchant routing after the base gateway
  is stable.
- Refund and dispute handling as controlled server-side workflows.

### Out of Scope for v1

- Direct card data capture by the payment-adapter.
- Unhosted payment forms that would expose the adapter to PAN, CVC, or expiry
  data.
- Subscription billing.
- ACH or bank transfer support while `Gateway` still maps one implementation to
  one `PaymentType`.
- Client-supplied success or cancel URLs.
- Automatic token revocation or clawback after a refund or dispute.

## Design Principles

- Stripe must be treated as an external payment processor, not as a source of
  truth for internal identifiers.
- Internal `quoteId` values remain authoritative and are included in Stripe
  metadata for correlation.
- Hosted checkout and verified webhooks are the only supported payment
  confirmation path.
- The module must fail closed on signature, amount, currency, merchant, or
  state-transition mismatches.
- Stripe-specific details stay in Stripe-specific classes and entities instead
  of overloading Lightning-oriented fields with misleading names.
- Implementation must follow Clean Code, SOLID, constructor injection, and
  small-function guidance from `CLAUDE.md`.

## Gateway Contract Alignment

The current `Gateway` interface is Lightning-shaped, so the Stripe
implementation must be explicit about what is supported and what is not.

| `Gateway` member | Stripe decision | Notes |
| --- | --- | --- |
| `createMintQuote(Integer amount, String description)` | Supported | Generates an internal `quoteId`, creates a Stripe Checkout Session, persists a pending `GatewayQuote`, persists a pending `GatewayPayment`, and returns the internal `quoteId`. |
| `createMeltQuote(Integer amount, String request, String description)` | Not supported in v1 | Refunds are not equivalent to Cashu melt quotes. Throw `UnsupportedOperationException` with clear context. |
| `createMeltQuote(String request)` | Not supported in v1 | Same rationale as above. |
| `getRequest(String quoteId)` | Supported | Returns the Checkout Session URL stored in `GatewayQuote.request`. |
| `checkPaymentStatus(String quoteId)` | Supported | Reads local state first. Stripe API lookup is recovery logic only and must not bypass webhook verification rules. |
| `getPaymentPreimage(String quoteId)` | Supported for compatibility | Returns the stable Stripe payment identifier stored in `GatewayPayment.paymentId`. The name is retained only because the interface is legacy. |
| `pay(String request)` | Not supported in v1 | Checkout is customer-driven, not a server-side push payment. Throw `UnsupportedOperationException`. |
| `getAmount(String quoteId)` | Supported | Returns the persisted quote amount in minor units. |
| `getPaymentExpiry(String quoteId)` | Supported | Returns the persisted quote expiry, not a hardcoded value. |
| `getFeeReserve(String quoteId)` | Returns `0` | Stripe processing costs are not Lightning routing fees. Any surcharge must be folded into the quoted amount upstream. |
| `getPaymentType()` | Returns `PaymentType.CREDIT_CARD` | v1 scope is card-backed hosted checkout. |
| `getGatewayId()` / `getName()` | Returns `stripe` | Use one stable identifier throughout logs, entities, and configuration. |
| `supports(PaymentMethod method)` | `@Supports(PaymentMethod.CREDIT_CARD)` | Do not advertise bank transfer support until the type model is widened. |

## Module Structure

```text
payment-adapter/
├── payment-adapter-core/
│   ├── payment-adapter-common
│   ├── payment-adapter-model
│   ├── payment-adapter-rest
│   └── payment-adapter-client
├── payment-adapter-stripe/
│   ├── payment-adapter-stripe-gateway
│   ├── payment-adapter-stripe-webhook
│   └── payment-adapter-stripe-connect
└── payment-adapter-webhook
```

### `payment-adapter-stripe-gateway`

Owns Checkout Session creation, quote persistence, Stripe status recovery, and
translation between Stripe objects and core entities.

Required classes:

- `StripeGateway`
- `StripeGatewayProperties`
- `StripeCheckoutService`
- `StripeQuoteService`
- `StripePaymentStatusService`
- `StripeExceptionTranslator`
- `StripeSessionMapper`

### `payment-adapter-stripe-webhook`

Owns raw webhook receipt, signature verification, replay protection, event
routing, state transitions, and event publication to downstream settlement
logic.

Required classes:

- `StripeWebhookController`
- `StripeWebhookSignatureVerifier`
- `StripeWebhookEventService`
- `StripeCheckoutCompletedHandler`
- `StripeAsyncPaymentSucceededHandler`
- `StripeAsyncPaymentFailedHandler`
- `StripeChargeRefundedHandler`
- `StripeDisputeCreatedHandler`

### `payment-adapter-stripe-connect`

Optional module for merchant onboarding and destination-charge routing. It must
remain isolated from the base gateway so card acceptance can ship without
merchant-payout complexity.

Required classes:

- `StripeConnectService`
- `StripeConnectProperties`
- `StripeAccountOnboardingService`
- `StripeConnectedAccountRepository`

## Data Model

### Core Entities

The Stripe gateway must reuse the shared entities without changing their core
meaning.

#### `GatewayQuote`

Use fields as follows:

- `quoteId`: internal UUID generated by the adapter.
- `invoiceId`: Stripe Checkout Session ID.
- `request`: Checkout Session URL.
- `amount`: integer amount in minor units.
- `unit`: ISO-4217 currency code in lowercase.
- `state`: `PENDING` until a verified Stripe webhook marks it paid.
- `direction`: `RECEIVE`.

#### `GatewayPayment`

Create a pending payment record during quote creation so
`/payment/search/findByQuoteId` does not depend on a later webhook to exist.

Use fields as follows:

- `request`: Checkout Session URL.
- `paymentId`: nullable until Stripe confirms the PaymentIntent.
- `quoteId`: internal quote identifier.
- `state`: `PENDING` on creation, `PAID` after successful webhook processing.
- `sourceCurrency`: same currency as the quote.
- `amount`: integer amount in minor units.
- `totalAmount`: same as amount for v1 unless surcharges are modeled upstream.
- `paymentType`: `PaymentType.CREDIT_CARD`.
- `gatewayId`: `stripe`.
- `idempotencyKey`: outbound idempotency key used for Checkout Session creation.
- `webhookProcessedAt`: timestamp set only after a verified state transition.

Do not overload `paymentHash` or `paymentPreimage` with fake Lightning values.
If the legacy `getPaymentPreimage` method is called, return `paymentId`.

### Stripe-Specific Entities

Stripe has lifecycle states that do not fit the shared `State` enum. Capture
them in Stripe-specific persistence instead of corrupting the generic model.

#### `StripePaymentReference`

```java
@Entity
@Table(name = "stripe_payment_references")
public class StripePaymentReference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String quoteId;

    @Column(nullable = false, unique = true)
    private String checkoutSessionId;

    @Column(unique = true)
    private String paymentIntentId;

    @Column(unique = true)
    private String chargeId;

    private String connectedAccountId;
    private String stripeStatus;
    private boolean livemode;
    private String lastEventId;
    private Integer refundedAmountMinor;
    private boolean disputed;
    private Instant createdAt;
    private Instant updatedAt;
}
```

#### `ProcessedStripeWebhookEvent`

```java
@Entity
@Table(name = "stripe_webhook_events")
public class ProcessedStripeWebhookEvent {

    @Id
    private String eventId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String payloadHash;

    @Column(nullable = false)
    private boolean livemode;

    @Column(nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StripeWebhookProcessingStatus processingStatus;

    private Instant processedAt;
    private String lastError;
}
```

`StripeWebhookProcessingStatus` should define at least `RECEIVED`,
`PROCESSING`, `PROCESSED`, and `FAILED`.

#### `ConnectedStripeAccount`

Keep this entity in the Stripe Connect module because it is Stripe-specific and
not reused by Lightning or Cash modules.

## Payment Flow

### Quote Creation

1. Validate the request before calling Stripe.
2. Generate a server-side `quoteId`.
3. Build the outbound idempotency key as `stripe:checkout:{quoteId}`.
4. Create the Checkout Session using hosted Stripe Checkout only.
5. Persist `GatewayQuote`, `GatewayPayment`, and `StripePaymentReference`.
6. Return the internal `quoteId`.

Required Checkout Session fields:

- `mode=payment`
- server-configured `success_url`
- server-configured `cancel_url`
- explicit `expires_at`
- `client_reference_id=quoteId`
- metadata containing only internal identifiers such as `quote_id`
- explicit currency and amount

If local persistence fails after Stripe created the session, the retry path must
reuse the same idempotency key and recover the existing session instead of
creating a second charge opportunity.

### Webhook Confirmation

1. Accept the raw request body as `byte[]` or an unmodified `String`.
2. Verify the `Stripe-Signature` header with the configured secret and a bounded
   timestamp tolerance.
3. Insert or lock the event row in `ProcessedStripeWebhookEvent` before
   executing business logic.
4. Treat the event as a duplicate only when its stored `processingStatus` is
   already `PROCESSED`.
5. Route only the allowlisted event types needed by the module.
6. Resolve the local records by `quote_id` metadata and stored Stripe IDs.
7. Validate amount, currency, livemode, gateway, and merchant account mapping.
8. Apply the state transition once inside a database transaction.
9. Publish an internal settlement event after the local transaction succeeds.
10. Mark the event row as `PROCESSED` only after the business transaction
   succeeds.
11. Return `200 OK` only after the event is durably recorded.

The webhook endpoint is the authoritative payment confirmation path. Success and
cancel redirects are user-experience features only and must never mark a quote
as paid.

### Refund and Dispute Flow

- Refunds must be initiated by a privileged server-side workflow, not by a
  public gateway method.
- Charge disputes must raise an operational alert with quote, payment, merchant,
  and Stripe identifiers.
- A refund or dispute does not mutate `GatewayQuote` or `GatewayPayment` back to
  `PENDING`. Those states represent the historical fact that value was issued.
- Stripe-native refund and dispute status must be tracked in
  `StripePaymentReference`.

## Security Requirements

### Cardholder Data Boundary

- The adapter must never receive, log, store, or proxy raw card numbers, CVC,
  expiry dates, or PaymentMethod details.
- Only Stripe-hosted Checkout is allowed in v1.
- Metadata must contain internal identifiers only. Do not place customer email,
  name, address, note text, or free-form merchant input in metadata.

### Secret Management

- Load Stripe API keys and webhook secrets from environment variables or a
  secret manager.
- Secrets must not have fallback defaults.
- Separate live and test credentials.
- Use distinct webhook secrets for payment and Connect endpoints.
- Startup must fail fast if required secrets are missing.

### Input Validation

- Amounts must be positive integers in minor units.
- Currency must be on a configured allowlist.
- Description text must be length-bounded before it reaches Stripe.
- Success and cancel URLs must come from configuration only.
- Merchant account routing must come from trusted server-side data, never from a
  client parameter.

### Webhook Security

- Verify signatures against the raw payload.
- Reject missing signatures, stale timestamps, malformed JSON, and unknown event
  types.
- Maintain separate endpoints and secrets for Checkout payment events and
  Connect account events.
- Ignore unsupported events with a safe `200 OK` after signature verification so
  Stripe does not retry indefinitely.

### Replay and Duplicate Protection

- Every state-changing Stripe API call must include an idempotency key.
- Every webhook event must be deduplicated using a persistent unique key on
  `eventId`.
- Deduplication records must distinguish `PROCESSED` from failed attempts so a
  transient error does not permanently block a legitimate retry.
- Payment confirmation logic must be safe under concurrent duplicate deliveries.
- The quote settlement path must be idempotent so a replay cannot issue value
  twice.

### State and Amount Integrity

- Verify that Stripe amount and currency match the stored quote before marking a
  quote as paid.
- Verify the event belongs to the expected Checkout Session and, when present,
  the expected PaymentIntent and connected account.
- Accept only valid state transitions, normally `PENDING -> PAID`.
- Never trust a client redirect, frontend callback, or query parameter as proof
  of payment.

### Logging and Data Minimization

- Use structured logs with `quoteId`, `checkoutSessionId`, `paymentId`, and
  `eventId`.
- Do not log raw webhook bodies in production.
- Mask secrets and sensitive headers.
- Avoid logging customer email or billing details even if Stripe includes them
  in webhook payloads.

### Connect-Specific Controls

- A merchant must prove ownership of the account being onboarded.
- Store only the Stripe account ID and minimal status flags.
- Protect Connect onboarding callbacks with a server-generated state token.
- Destination charges must use the merchant account linked to the authenticated
  merchant record, not a request parameter.

### Operational Controls

- Alert on dispute creation, repeated signature failures, and persistent webhook
  retries.
- Record `livemode` on Stripe-specific entities to prevent mixing test and live
  traffic.
- Retain enough audit data to investigate fraud without retaining cardholder
  data.

## Coding Rules for the Implementation

The implementation must adopt the engineering rules in `CLAUDE.md`.

- Use constructor injection everywhere. Do not instantiate `QuoteClient`,
  `PaymentClient`, or Stripe SDK wrappers directly inside controllers or gateway
  methods.
- Keep methods small and single-purpose. Break quote creation into focused
  helpers such as `buildCheckoutSession`, `persistPendingQuote`, and
  `validateStripeAmount`.
- Use intention-revealing names such as `stripePaymentReferenceRepository`
  instead of vague names such as `manager` or `data`.
- Wrap Stripe SDK failures in unchecked domain exceptions with context, for
  example `StripeCheckoutCreationException(quoteId, currency, cause)`.
- Keep configuration in `StripeGatewayProperties` and other dedicated property
  classes. Do not hardcode expiry, URLs, currencies, or tolerance values.
- Use Lombok where it removes boilerplate cleanly, especially
  `@RequiredArgsConstructor`, `@Slf4j`, and `@Builder` for DTOs.
- Prefer interfaces for services that talk to Stripe so tests can mock behavior
  without static SDK calls.
- Use Java 21 virtual threads for parallel I/O only when there is real
  concurrency value, such as reconciling multiple Stripe objects or issuing a
  REST update and an audit lookup concurrently.
- Do not pin application behavior to a specific Stripe SDK version inside this
  spec. Dependency versions belong in the parent `pom.xml` configuration section.
- Add a plain-English comment above every test method.

## Configuration

Required environment variables:

```bash
STRIPE_SECRET_KEY=
STRIPE_WEBHOOK_SECRET=
STRIPE_SUCCESS_URL=
STRIPE_CANCEL_URL=
STRIPE_ALLOWED_CURRENCIES=
STRIPE_DEFAULT_CURRENCY=
STRIPE_CHECKOUT_EXPIRY_SECONDS=
STRIPE_WEBHOOK_TOLERANCE_SECONDS=
```

Optional environment variables:

```bash
STRIPE_CONNECT_ENABLED=false
STRIPE_CONNECT_WEBHOOK_SECRET=
STRIPE_MAX_AMOUNT_MINOR=
STRIPE_MIN_AMOUNT_MINOR=
STRIPE_STATEMENT_DESCRIPTOR=
```

Configuration rules:

- Secrets must not be committed.
- Currency and amount bounds must be configurable.
- Redirect URLs must be absolute HTTPS URLs in production.
- Stripe SDK and plugin versions must be managed in the parent `pom.xml`.

## Testing

### Unit Tests

- `StripeGatewayTest`
- `StripeCheckoutServiceTest`
- `StripeWebhookSignatureVerifierTest`
- `StripeWebhookEventServiceTest`
- `StripeDisputeCreatedHandlerTest`
- `StripeExceptionTranslatorTest`

### Integration Tests

- Verify quote creation persists `GatewayQuote`, `GatewayPayment`, and
  `StripePaymentReference` consistently.
- Verify webhook delivery updates the existing payment exactly once.
- Verify duplicate webhook delivery does not create a second settlement.
- Verify mismatched amount, currency, or account mapping is rejected.
- Verify unsupported gateway methods throw the documented exceptions.

### Security Tests

- Invalid signature.
- Stale signature timestamp.
- Duplicate event replay.
- Tampered metadata.
- Refund or dispute event against an unknown quote.
- Test-mode event sent to a live-mode record.

### Test Infrastructure

- CI tests should use deterministic mocks such as WireMock or `stripe-mock`
  rather than live Stripe credentials.
- Manual smoke testing may use Stripe CLI in test mode, but that is not a
  substitute for automated integration coverage.
- Run `./mvnw -q verify` from the repository root before merging code changes.

## Documentation and Release Impact

- Add the new Stripe modules to the parent `pom.xml`.
- Update API and configuration reference docs when endpoints or properties are
  introduced.
- Update `CHANGELOG.md` when the implementation lands.
- Link this spec from `docs/README.md`.

## Implementation Plan

### Phase 1: Core Gateway

- Create the `payment-adapter-stripe` aggregator and module POMs.
- Implement `StripeGatewayProperties` and `StripeGateway`.
- Implement hosted Checkout Session creation with outbound idempotency.
- Persist pending `GatewayQuote`, `GatewayPayment`, and
  `StripePaymentReference`.
- Document unsupported melt and pay operations explicitly.

### Phase 2: Secure Webhooks

- Implement raw-body webhook controller and signature verifier.
- Add persistent event deduplication.
- Implement strict amount, currency, and state-transition validation.
- Publish an internal payment-confirmed event after durable state update.
- Add dispute and refund handlers with alerting hooks.

### Phase 3: Connect

- Implement connected-account persistence and onboarding.
- Add secure destination-charge routing.
- Add separate Connect webhook endpoint and signature verification.
- Enforce merchant-to-account ownership checks.

### Phase 4: Verification and Docs

- Add unit, integration, and security tests.
- Update reference documentation and examples.
- Run `./mvnw -q verify`.

## Open Questions

1. Should the shared `State` model be expanded to include failed, refunded, and
   disputed states, or should those remain Stripe-specific forever?
2. Which service owns fiat-to-satoshi conversion and price locking before
   `createMintQuote` is called?
3. Should Stripe Connect ship in the first release, or after the single-account
   gateway and webhook path are proven stable?
