package xyz.tcheeric.payment.adapter.test.e2e;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import xyz.tcheeric.payment.adapter.cash.gateway.ratelimit.CashRateLimiter;
import xyz.tcheeric.payment.adapter.core.model.repository.CashIntentRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashReceiptRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;

@SpringBootTest(
        classes = E2ETestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Testcontainers
@ActiveProfiles("test")
public abstract class BaseE2ETest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("payment_adapter_e2e")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @LocalServerPort
    protected int port;

    @Autowired
    protected CashInvoiceRepository invoiceRepository;

    @Autowired
    protected CashIntentRepository intentRepository;

    @Autowired
    protected CashReceiptRepository receiptRepository;

    @Autowired
    protected CashRateLimiter rateLimiter;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StripePaymentReferenceRepository stripePaymentReferenceRepository;

    @Autowired
    protected ProcessedStripeWebhookEventRepository processedStripeWebhookEventRepository;

    protected void cleanDatabase() {
        processedStripeWebhookEventRepository.deleteAll();
        stripePaymentReferenceRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM payment");
        jdbcTemplate.update("DELETE FROM quote");
        receiptRepository.deleteAll();
        intentRepository.deleteAll();
        invoiceRepository.deleteAll();
        resetRateLimiter();
    }

    protected void configureGatewayClientBaseUrl() {
        System.setProperty("gateway.api.base_url", "http://localhost:" + port);
        System.setProperty("gateway.api.port", String.valueOf(port));
    }

    protected void resetRateLimiter() {
        ReflectionTestUtils.setField(rateLimiter, "invoicesPerHour", 1000);
        var buckets = ReflectionTestUtils.getField(rateLimiter, "buckets");
        if (buckets instanceof java.util.Map<?, ?> map) {
            map.clear();
        }
    }
}
