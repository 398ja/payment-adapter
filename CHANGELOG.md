# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
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
