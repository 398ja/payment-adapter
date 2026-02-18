# Building

Full verification (build, test, integration test):

```bash
./mvnw -q verify
```

Compile all modules from the project root:

```bash
./mvnw package
```

Build a specific module with its dependencies:

```bash
./mvnw -pl payment-adapter-core/payment-adapter-rest -am package
```

Quick build skipping tests:

```bash
./mvnw clean install -DskipTests
```

Build the cash modules:

```bash
./mvnw -pl payment-adapter-cash -am package
```

See [requirements](requirements.md) for setup details.
