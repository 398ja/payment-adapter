# Changelog

This document summarizes notable changes to the cashu-gateway project. Versions follow semantic versioning when possible.

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
