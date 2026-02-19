# Running

Start PostgreSQL, phoenixd, and the REST API using Docker Compose:

```bash
docker-compose up
```

The REST service uses the following environment variables:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://payment-adapter-db:5432/payment-adapter
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=password
```

To run the service directly without containers:

```bash
./mvnw -pl payment-adapter-core/payment-adapter-rest spring-boot:run
```

Ensure the project is [built](building.md) before starting.
