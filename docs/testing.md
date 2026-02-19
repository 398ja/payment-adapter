# Testing

Run all tests:

```bash
./mvnw test
```

Full verification including integration tests:

```bash
./mvnw -q verify
```

Run tests for a specific module:

```bash
./mvnw -pl payment-adapter-cash/payment-adapter-cash-gateway test
./mvnw -pl payment-adapter-ln/payment-adapter-ln-phoenixd test
```

The aggregated JaCoCo coverage report is generated at `target/site/jacoco-aggregate/index.html`.
