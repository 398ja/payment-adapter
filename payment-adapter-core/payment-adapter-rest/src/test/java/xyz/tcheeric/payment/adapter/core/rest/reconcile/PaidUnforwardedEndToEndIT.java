package xyz.tcheeric.payment.adapter.core.rest.reconcile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.rest.repository.QuoteRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The #462 machinery through the paths it will actually be used by: a real Spring context, real
 * JPA against a real database, and the real {@code /actuator/prometheus} scrape.
 *
 * <p>Everything else covering this change is a unit test over mocks. Those prove the decisions,
 * but they cannot see a JPQL query that does not compile, a column Hibernate never created, an
 * enum comparison that behaves differently in SQL than in Java, or a gauge that is registered in
 * a throwaway registry but never reaches the exposition the alert reads. Each of those has
 * happened in this codebase within the last week — the V11 CREATE INDEX that failed because
 * Flyway runs before Hibernate was caught by a build, not by review, and two cashu-mint gauges
 * sat bound-but-never-polled with every test green.
 *
 * <p>So this asserts the three things the issue is actually judged on, end to end:
 * the query finds a stranded payment, the gauge exports the count, and the scrape carries it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
                "management.prometheus.metrics.export.enabled=true",
                "management.server.port=0"
        })
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
@ActiveProfiles("test")
class PaidUnforwardedEndToEndIT {

    @Autowired
    private QuoteRepository quotes;

    @Autowired
    private PaidUnforwardedGauge gauge;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Actuator runs on its own port here, as it should anywhere reachable from outside:
     * /actuator/prometheus exposes payment volumes and now the count of payments the mint has
     * not acknowledged. TestRestTemplate targets the API port, so the scrape has to be addressed
     * explicitly or it answers 404 — which is how this test first failed.
     */
    @LocalManagementPort
    private int managementPort;

    /**
     * Each test seeds its own rows and asserts only about them, because QuoteRepository is a
     * PagingAndSortingRepository with no deleteAll and this context is shared. The counting
     * assertions therefore measure a delta rather than an absolute.
     */
    private long baselineUnforwarded;

    @BeforeEach
    void recordBaseline() {
        baselineUnforwarded = quotes.countPaidButNotForwarded();
    }

    private GatewayQuote persistQuote(State state, Instant createdAt, Instant mintNotifiedAt) {
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(UUID.randomUUID().toString());
        quote.setInvoiceId(UUID.randomUUID().toString());
        quote.setAmount(30);
        quote.setUnit("sat");
        quote.setDirection(Direction.RECEIVE);
        quote.setState(state);
        quote.setCreatedAt(createdAt);
        quote.setMintNotifiedAt(mintNotifiedAt);
        return quotes.save(quote);
    }

    /**
     * The column exists and round-trips. Trivial-looking, and the reason it is here is that
     * V11 adds it by {@code ALTER TABLE IF EXISTS} while Hibernate creates it from the entity on
     * a fresh database — two mechanisms for one column, and a unit test over a mock sees neither.
     */
    @Test
    @DisplayName("mint_notified_at survives a round trip through the real schema")
    void columnRoundTrips() {
        Instant stamped = Instant.now().minus(Duration.ofMinutes(1));
        GatewayQuote saved = persistQuote(State.PAID, Instant.now(), stamped);

        GatewayQuote reloaded = quotes.findByQuoteId(saved.getQuoteId()).orElseThrow();

        assertThat(reloaded.getMintNotifiedAt())
                .as("the stamp must be readable after a round trip, or the sweep re-delivers forever")
                .isCloseTo(stamped, within(1000));
    }

    /**
     * The sweep query against real SQL. The JPQL compares an enum by fully-qualified constant and
     * filters on a nullable timestamp; both are the kind of thing that passes review and fails
     * at runtime.
     */
    @Test
    @DisplayName("the sweep query finds a settled payment the mint was never told about")
    void sweepQueryFindsTheStrandedPayment() {
        Instant longAgo = Instant.now().minus(Duration.ofHours(2));
        GatewayQuote stranded = persistQuote(State.PAID, longAgo, null);
        // Noise that must NOT be selected: delivered, unpaid, and too recent.
        GatewayQuote delivered = persistQuote(State.PAID, longAgo, Instant.now());
        GatewayQuote unpaid = persistQuote(State.PENDING, longAgo, null);
        GatewayQuote inFlight = persistQuote(State.PAID, Instant.now(), null);

        var found = quotes.findPaidButNotForwarded(
                Instant.now().minus(Duration.ofMinutes(5)),
                org.springframework.data.domain.Limit.of(100));

        assertThat(found)
                .as("the stranded payment must be selected")
                .extracting(GatewayQuote::getQuoteId)
                .contains(stranded.getQuoteId());
        assertThat(found)
                .as("the delivered, unpaid and in-flight quotes must not be")
                .extracting(GatewayQuote::getQuoteId)
                .doesNotContain(delivered.getQuoteId(), unpaid.getQuoteId(), inFlight.getQuoteId());
    }

    /**
     * The gauge count, against the same real SQL. Deliberately unbounded by time where the sweep
     * is bounded, so this also pins that the two queries differ in the way they are meant to.
     */
    @Test
    @DisplayName("the gauge query counts every unforwarded payment, including recent ones")
    void gaugeQueryCountsTheStandingLiability() {
        persistQuote(State.PAID, Instant.now().minus(Duration.ofHours(2)), null);
        persistQuote(State.PAID, Instant.now(), null);
        persistQuote(State.PAID, Instant.now(), Instant.now());
        persistQuote(State.PENDING, Instant.now(), null);

        assertThat(quotes.countPaidButNotForwarded() - baselineUnforwarded)
                .as("the standing liability includes payments still inside the sweep's grace period")
                .isEqualTo(2L);
    }

    /**
     * The acceptance path itself: a stranded payment must reach the Prometheus exposition, because
     * that is the only surface the alert reads. A gauge that is correct in the registry and absent
     * from the scrape is an alert that never fires.
     */
    @Test
    @DisplayName("a stranded payment reaches the real /actuator/prometheus scrape")
    void strandedPaymentReachesTheScrape() {
        persistQuote(State.PAID, Instant.now().minus(Duration.ofHours(2)), null);
        persistQuote(State.PAID, Instant.now().minus(Duration.ofHours(3)), null);

        gauge.pollTick();

        assertThat(gaugeValueOnScrape() - baselineUnforwarded)
                .as("the alert rule reads this series; if it is absent or stale the money is invisible")
                .isEqualTo(2.0);
    }

    /**
     * And it must come back down once the payments are delivered. An alert that cannot clear is
     * one that gets muted, which is the same as not having it.
     */
    @Test
    @DisplayName("the scrape returns to zero once the payments are delivered")
    void scrapeClearsOnceDelivered() {
        GatewayQuote stranded = persistQuote(State.PAID, Instant.now().minus(Duration.ofHours(2)), null);
        gauge.pollTick();
        assertThat(gaugeValueOnScrape() - baselineUnforwarded).isEqualTo(1.0);

        // What the sweep does on a successful re-delivery.
        stranded.setMintNotifiedAt(Instant.now());
        quotes.save(stranded);
        gauge.pollTick();

        assertThat(gaugeValueOnScrape() - baselineUnforwarded)
                .as("an alert that cannot clear is one nobody reads twice")
                .isZero();
    }

    /**
     * The series must be present reading zero on a healthy system. {@code absent()} and a healthy
     * {@code 0} are indistinguishable to a {@code > 0} rule, which is why the deploy carries a
     * separate absence alert — and why this asserts presence rather than merely a value.
     */
    @Test
    @DisplayName("the series is present at zero when nothing is stranded")
    void seriesIsPresentAtZeroWhenHealthy() {
        gauge.pollTick();

        assertThat(scrapeBody())
                .as("a gauge that only appears once money is lost is an alert that never arms")
                .contains(PaidUnforwardedGauge.METRIC_NAME);
        assertThat(gaugeValueOnScrape())
                .as("present, not absent: gaugeValue answers -1 for a missing family")
                .isGreaterThanOrEqualTo(0.0);
    }

    private String scrapeBody() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);
        assertThat(response.getStatusCode().value())
                .as("the scrape must be reachable, or none of this is observable")
                .isEqualTo(200);
        return response.getBody() == null ? "" : response.getBody();
    }

    private double gaugeValueOnScrape() {
        Matcher matcher = Pattern.compile(
                        "^" + Pattern.quote(PaidUnforwardedGauge.METRIC_NAME) + "(?:\\{[^}]*})?\\s+([0-9.E+-]+)$",
                        Pattern.MULTILINE)
                .matcher(scrapeBody());
        // -1 rather than 0 for an absent family: absent and zero must never be conflated here,
        // since that conflation is the whole reason the absence alert exists.
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : -1.0;
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long millis) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
                millis, java.time.temporal.ChronoUnit.MILLIS);
    }
}
