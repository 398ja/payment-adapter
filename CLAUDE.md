# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build all modules
./mvnw package

# Build specific module
./mvnw -pl payment-adapter-rest package

# Run all tests
./mvnw test

# Full verification (required before committing)
./mvnw -q verify

# Run REST service directly
./mvnw -pl payment-adapter-rest spring-boot:run

# Start full Docker stack (PostgreSQL + phoenixd + REST + webhook)
docker-compose up

# JaCoCo coverage report location after tests
# target/site/jacoco-aggregate/index.html
```

## Architecture

Payment Adapter is a modular Spring Boot application providing REST services for creating and settling Lightning Network invoices via the Cashu protocol.

**Module Structure:**
```
payment-adapter (parent POM)
├── payment-adapter-model      → JPA entities (GatewayQuote, GatewayPayment)
├── payment-adapter-common     → Gateway interface and shared exceptions
├── payment-adapter-rest       → Spring Boot REST app (port 8080)
├── payment-adapter-client     → Java HTTP client library
├── payment-adapter-phoenixd   → Gateway implementation for phoenixd Lightning node
├── payment-adapter-webhook    → Servlet webhook handler (port 9090)
├── payment-adapter-dummy      → Mock Gateway for testing
```

**Key Patterns:**
- Gateway Pattern: Abstract `Gateway` interface with implementations (`PhoenixdGateway`, `DummyGateway`)
- Spring Data REST: Auto-generated REST endpoints from JPA repositories
- Dependency Inversion: Modules depend on abstractions in `payment-adapter-common`

**REST Endpoints (Spring Data REST):**
- `GET/POST /quote` - Quote operations
- `GET /quote/search/findByQuoteId?quoteId=...` - Search by external ID
- `GET/POST /payment` - Payment operations
- `GET /payment/search/findByQuoteId?quoteId=...` - Payment lookup by quote

## Coding Conventions

- Java 21, four-space indentation, braces on same line
- Use Lombok annotations (`@Data`, `@Slf4j`, `@NoArgsConstructor`)
- Follow Conventional Commits for commit messages
- Test naming: `*Test.java` (unit), `*IT.java` (integration)
- Add comment above each test method describing what it tests
- Features must comply with Cashu NUT specifications: https://github.com/cashubtc/nuts

## Testing Requirements

- Run `./mvnw -q verify` before committing
- Unit tests cover edge cases; integration tests verify end-to-end
- Use H2 in-memory database for tests, WireMock for HTTP mocking
- Include test output in PR descriptions

## Changelog & Versioning

- Follow Semantic Versioning
- Update `CHANGELOG.md` using Keep a Changelog format after version changes
- Versions maintained in parent `pom.xml`

## Documentation

- Follow Diátaxis framework (tutorial, how-to, reference, explanation)
- Place docs under `docs/<section>` and link from `docs/README.md`
