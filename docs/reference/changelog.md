# Changelog

This document summarizes notable changes to the cashu-gateway project. Versions follow semantic versioning when possible.

## 0.2.1

- PhoenixdGateway: load `phoenixd.properties` when instantiated outside Spring, ensuring `webhook.base_url` and other settings are initialized in cashu-mint and other environments that use `new PhoenixdGateway()`.
- PhoenixdGateway: add null guard and clearer error for missing `webhook.base_url` to prevent NPE in `getWebhookUrl()`.

Upgrade notes:
- If you previously instantiated `PhoenixdGateway` manually, no changes are required; it now self-initializes from the classpath.
- When running under Spring, `@Value` injection behavior is unchanged.

## 0.2.0

- Baseline release for gateway modules and clients.

