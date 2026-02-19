package xyz.tcheeric.payment.adapter.test.e2e;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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

    @Autowired
    protected CashInvoiceRepository invoiceRepository;

    @Autowired
    protected CashIntentRepository intentRepository;

    @Autowired
    protected CashReceiptRepository receiptRepository;

    @Autowired
    protected CashRateLimiter rateLimiter;

    protected void cleanDatabase() {
        receiptRepository.deleteAll();
        intentRepository.deleteAll();
        invoiceRepository.deleteAll();
        resetRateLimiter();
    }

    protected void resetRateLimiter() {
        ReflectionTestUtils.setField(rateLimiter, "invoicesPerHour", 1000);
        var buckets = ReflectionTestUtils.getField(rateLimiter, "buckets");
        if (buckets instanceof java.util.Map<?, ?> map) {
            map.clear();
        }
    }
}
