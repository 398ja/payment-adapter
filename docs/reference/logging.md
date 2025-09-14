# Logging Reference

This guide shows how to enable and tune logging for the Cashu Gateway modules and clients.

SLF4J is used as the logging API, with Logback as the default implementation in examples.

## Spring Boot applications

- Add properties to your `application.properties` or `application.yml`:

  - `logging.level.root=INFO`
  - `logging.level.xyz.tcheeric=DEBUG` (to see detailed gateway/client logs)

- Example (`application.properties`):

  ```properties
  logging.level.root=INFO
  logging.level.xyz.tcheeric=DEBUG
  logging.pattern.console=%d{yyyy-MM-dd'T'HH:mm:ss.SSSX} %-5level %logger{36} - %msg%n
  ```

## Plain Java applications (non‑Spring)

- Place a `logback.xml` on the classpath root (e.g. `src/main/resources/logback.xml`).
- Use the sample provided at `docs/examples/logback.xml` as a starting point.
- The gateway client module ships a default `logback.xml` that logs to console and to a rolling file under `logs/cashu-gateway-client.log`. To override it, place your own `logback.xml` earlier on the classpath in your application.

## Environment variables and JVM args

- Spring Boot: `--logging.level.xyz.tcheeric=DEBUG` or `-Dlogging.level.xyz.tcheeric=DEBUG`.
- Logback (non‑Spring) is configured via `logback.xml`; JVM args are not typically used for per‑logger levels without additional tooling.

## Log file location

- Default file for the gateway client module: `logs/cashu-gateway-client.log` (rolling by size and day, 10MB/file, 14 days, 1GB cap).
- Customize by copying the shipped `logback.xml` to your app and changing `<property name="LOG_DIR" .../>` and `<property name="LOG_FILE" .../>`.

## Module‑specific loggers and files

- `xyz.tcheeric.gateway` – gateway clients and common code
- `xyz.tcheeric.gateway.phoenixd` – phoenixd gateway integration
- `xyz.tcheeric.gateway.webhook` – webhook handler

Default log files provided by modules:
- Client: `logs/cashu-gateway-client.log`
- REST: `logs/cashu-gateway-rest.log`
- Webhook: `logs/cashu-gateway-webhook.log`
