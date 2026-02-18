# Logging Reference

This guide shows how to enable and tune logging for the Payment Adapter modules and clients.

SLF4J is used as the logging API, with Logback as the default implementation in examples.

## Spring Boot applications

- Add properties to your `application.properties` or `application.yml`:

  - `logging.level.root=INFO`
  - `logging.level.xyz.tcheeric=DEBUG` (to see detailed adapter/client logs)

- Example (`application.properties`):

  ```properties
  logging.level.root=INFO
  logging.level.xyz.tcheeric=DEBUG
  logging.pattern.console=%d{yyyy-MM-dd'T'HH:mm:ss.SSSX} %-5level %logger{36} - %msg%n
  ```

## Plain Java applications (non‑Spring)

- Place a `logback.xml` on the classpath root (e.g. `src/main/resources/logback.xml`).
- Use the sample provided at `docs/examples/logback.xml` as a starting point.

## Environment variables and JVM args

- Spring Boot: `--logging.level.xyz.tcheeric=DEBUG` or `-Dlogging.level.xyz.tcheeric=DEBUG`.
- Logback (non‑Spring) is configured via `logback.xml`; JVM args are not typically used for per‑logger levels without additional tooling.

## Module‑specific loggers

- `xyz.tcheeric.payment.adapter.core` – model, common, REST, and client code
- `xyz.tcheeric.payment.adapter.cash` – cash gateway, nostr, and webhook modules
- `xyz.tcheeric.payment.adapter.ln` – phoenixd gateway integration
- `xyz.tcheeric.payment.adapter.webhook` – webhook handler and forwarder
