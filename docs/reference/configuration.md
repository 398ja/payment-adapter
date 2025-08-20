# Configuration

Modules supply `app.properties` files for custom settings. Common overrides for the REST service can be provided via environment variables:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/cashu-gateway
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=password
```

Set the system property `gateway.api.base_url` to change the base URL used by the client library.
