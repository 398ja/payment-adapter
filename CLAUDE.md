# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Full verification (required before committing)
./mvnw -q verify

# Build all modules
./mvnw package

# Skip tests for quick builds
./mvnw clean install -DskipTests

# Build specific module with dependencies
./mvnw -pl payment-adapter-core/payment-adapter-rest -am verify

# Run all tests
./mvnw test

# Run REST service directly
./mvnw -pl payment-adapter-core/payment-adapter-rest spring-boot:run

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
├── payment-adapter-core/
│   ├── payment-adapter-model      → JPA entities (GatewayQuote, GatewayPayment)
│   ├── payment-adapter-common     → Gateway interface and shared exceptions
│   ├── payment-adapter-rest       → Spring Boot REST app (port 8080)
│   └── payment-adapter-client     → Java HTTP client library
├── payment-adapter-ln/
│   ├── payment-adapter-ln-phoenixd → Gateway implementation for phoenixd Lightning node
│   ├── payment-adapter-ln-dummy    → Mock Gateway for testing
│   └── payment-adapter-ln-webhook  → Lightning webhook handler
├── payment-adapter-cash/
│   ├── payment-adapter-cash-nostr   → Nostr-based cash communication
│   ├── payment-adapter-cash-gateway → Cash gateway implementation
│   └── payment-adapter-cash-webhook → Cash webhook handler
└── payment-adapter-webhook        → Servlet webhook handler (port 9090)
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
- Follow Conventional Commits: `feat(scope): message`, `fix(scope): message`
- Prefer unchecked exceptions with context (operation + failure type)
- Follow SOLID principles; organize code by module responsibility
- Test naming: `*Test.java` (unit), `*IT.java` (integration)
- Add comment above each test method describing what it tests
- Features must comply with Cashu NUT specifications: https://github.com/cashubtc/nuts

### Secure Coding

- **Key Principles:** Input validation, output encoding, safe cryptography (BouncyCastle), and secrets management.
- **Review:** All PRs must be reviewed for security implications.

## Use Virtual Threads for Concurrency

This project uses Java 21 Virtual Threads (Project Loom) for efficient concurrency. Virtual Threads are enabled by default via `spring.threads.virtual.enabled=true`. **Always prefer Virtual Threads over platform threads for I/O-bound work.**

### When to Use Virtual Threads

| Scenario | Use Virtual Threads? | Pattern |
|----------|---------------------|---------|
| Lightning API calls (phoenixd) | Yes | `CompletableFuture` with VT executor |
| Database queries | Yes | Parallel queries with VT executor |
| Webhook delivery | Yes | VT handles blocking HTTP efficiently |
| File I/O | Yes | VT handles blocking efficiently |
| Cryptographic operations (signing) | No | CPU-bound, use parallel streams |
| Quick in-memory operations | No | Overhead not justified |

### Patterns and Examples

**1. Parallel I/O with CompletableFuture and VT Executor (Preferred)**

Use when you need results from multiple independent I/O operations:

```java
import java.util.concurrent.*;

// Parallel gateway queries with Virtual Threads
private List<QuoteStatus> fetchQuoteStatuses(List<String> quoteIds, Gateway gateway) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<QuoteStatus>> futures = quoteIds.stream()
            .map(id -> CompletableFuture.supplyAsync(() -> {
                return gateway.getQuoteStatus(id);
            }, executor))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
            .map(f -> f.getNow(null))
            .filter(Objects::nonNull)
            .toList();
    }
}
```

**2. Fire-and-Forget with @Async**

Use for event handlers that shouldn't block the caller:

```java
@Async  // Runs on VT via AsyncConfig
@EventListener
public void onPaymentSettled(PaymentSettledEvent event) {
    webhookService.notifySettlement(event.getPaymentId());
}
```

**3. Parallel Database Queries**

Use for fetching related entities:

```java
private QuoteDetails loadFullQuote(String quoteId) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var quoteFuture = CompletableFuture.supplyAsync(
            () -> quoteRepository.findByQuoteId(quoteId), executor);
        var paymentsFuture = CompletableFuture.supplyAsync(
            () -> paymentRepository.findByQuoteId(quoteId), executor);

        CompletableFuture.allOf(quoteFuture, paymentsFuture).join();

        return QuoteDetails.builder()
            .quote(quoteFuture.getNow(null))
            .payments(paymentsFuture.getNow(List.of()))
            .build();
    }
}
```

### Anti-Patterns to Avoid

**Do not use sequential I/O in loops when items are independent:**
```java
// BAD: Sequential blocking calls
for (String quoteId : quoteIds) {
    var status = gateway.getQuoteStatus(quoteId);  // Blocks
}
```

**Do not use synchronized for I/O operations (causes VT pinning):**
```java
// BAD: Pins virtual thread to carrier thread
synchronized (lock) {
    database.query(...);  // Pinned during entire I/O!
}

// GOOD: Use ReentrantLock instead
private final ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    database.query(...);  // VT can unmount during I/O
} finally {
    lock.unlock();
}
```

**Do not create platform thread pools for I/O work:**
```java
// BAD: Wastes platform threads on I/O
ExecutorService pool = Executors.newFixedThreadPool(10);

// GOOD: Use virtual thread executor
ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
```

### VT Configuration Reference

| Component | Configuration | Purpose |
|-----------|--------------|---------|
| Spring Boot | `spring.threads.virtual.enabled=true` | Use VT for request handling |
| Tomcat | `server.tomcat.threads.max=50` | Reduced (VTs handle concurrency) |
| `@Async` | `AsyncConfig` bean | VT executor for async methods |
| HTTP Client | `JdkClientHttpRequestFactory` | VT-friendly HTTP client |

### Debugging Virtual Threads

```bash
# Enable VT debugging output
-Djdk.tracePinnedThreads=full

# Monitor virtual thread count
jcmd <pid> Thread.dump_to_file -format=json threads.json
```

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
