# Changelog

This document summarizes notable changes to the payment-adapter project. Versions follow semantic versioning when possible.

## 0.6.0 - 2026-01-20

### Changed
- Renamed project from payment-gateway to payment-adapter (all module artifactIds and documentation updated)

## 0.5.0 - 2026-01-18

### Changed
- Renamed project from cashu-gateway to payment-gateway (all module artifactIds updated)
- Refactored PaymentController to use PaymentRepository and updated endpoint response type
- Added `-parameters` compiler flag for Spring MVC method parameter binding

### Added
- Validation for `amountSat` and `externalId` parameters in RequestValidatorFacade
- Validation for base URL host in GatewayClientConfig
- Enhanced key filtering to include additional sensitive terms
- Enhanced property logging with safety checks and profile filtering
- PaymentControllerIT integration test
- CLAUDE.md for AI assistant guidance

## 0.4.9

- Bump version and update cashu-lib to 0.11.1
- Add /payment/search/findByQuoteId endpoint alias

## 0.4.8

- Bump version and update cashu-lib to 0.8.1

## 0.3.2

- Project: bump parent and module versions to 0.3.2. No functional changes; housekeeping release to prepare next iteration.

## 0.3.1

- Phoenixd: reject unknown or mismatched quoteId during pay(), ensuring the wallet’s POST /mint/bolt11 quoteId matches the mint’s generated quote from POST /mint/quote/bolt11.
- Tests: add unit tests for quoteId consistency and unknown/stale IDs in both Phoenixd and Dummy gateways.

## 0.3.0

- REST: enable POST create for Quote and Payment via Spring Data REST and expose IDs.
- REST: return entity body on POST/PUT to satisfy client expectations and integration tests.
- Fix: remove incorrect @Override declarations in repositories to avoid compilation errors in some toolchains.
- Docs/Tests: add integration tests around create flows; minor doc updates.

## 0.2.1

- PhoenixdGateway: load `phoenixd.properties` when instantiated outside Spring, ensuring `webhook.base_url` and other settings are initialized in cashu-mint and other environments that use `new PhoenixdGateway()`.
- PhoenixdGateway: add null guard and clearer error for missing `webhook.base_url` to prevent NPE in `getWebhookUrl()`.

Upgrade notes:
- If you previously instantiated `PhoenixdGateway` manually, no changes are required; it now self-initializes from the classpath.
- When running under Spring, `@Value` injection behavior is unchanged.

## 0.2.2

- Client: constructor-first base URL resolution with layered fallbacks (system property, env var, gateway.properties, default), plus configurable port with default 8080.
- Client: default Logback config to log to console and rolling file; DEBUG logs for `xyz.tcheeric` by default. Tests include `logback-test.xml`.
- REST/Webhook: default Logback configs added to log to console and rolling files.
- Docs: logging reference, client configuration (incl. Docker Compose usage), and sample properties files.

## 0.2.0

- Baseline release for gateway modules and clients.
