package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.GiftOutcome;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

/** Business metrics for gifts: the state mapping, value sums, the zero state and scrape text. */
class GiftBusinessGaugeTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private StripePurchaseRepository purchases;
    private PrometheusMeterRegistry registry;
    private GiftBusinessGauge gauge;

    @BeforeEach
    void setUp() {
        purchases = mock(StripePurchaseRepository.class);
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        gauge = new GiftBusinessGauge(purchases, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** One grouped row as summariseGifts returns it. */
    private static Object[] row(String delivery, String giftKind, Status status, GiftOutcome outcome,
                                String currency, long count, long amount, Long remaining) {
        return new Object[] {delivery, giftKind, status, outcome, currency, count, amount,
                remaining == null ? amount : remaining};
    }

    private void given(Object[]... rows) {
        List<Object[]> list = new ArrayList<>(List.of(rows));
        when(purchases.summariseGifts(anyCollection())).thenReturn(list);
    }

    private String scrape() {
        return registry.scrape();
    }

    @Test
    void mapsEveryStatusAndOutcomeToOneContractState() {
        for (Status s : Status.values()) {
            String expected = s == Status.REFUNDED ? "returned" : "outstanding";
            assertEquals(expected, GiftBusinessGauge.itemState(s, null), s.name());
        }
        assertEquals("outstanding", GiftBusinessGauge.itemState(Status.DISCHARGED, GiftOutcome.PARTLY_USED));
        assertEquals("redeemed", GiftBusinessGauge.itemState(Status.DISCHARGED, GiftOutcome.CLAIMED));
        assertEquals("expired", GiftBusinessGauge.itemState(Status.DISCHARGED, GiftOutcome.EXPIRED));
        assertEquals("returned", GiftBusinessGauge.itemState(Status.DISCHARGED, GiftOutcome.RETURNED));
        // An unconfirmed discharge that was claimed is still redeemed; a refund always wins.
        assertEquals("redeemed", GiftBusinessGauge.itemState(Status.ISSUED, GiftOutcome.CLAIMED));
        assertEquals("returned", GiftBusinessGauge.itemState(Status.REFUNDED, GiftOutcome.CLAIMED));
    }

    @Test
    void theZeroStateIsBoundBeforeAnyPoll() {
        String text = scrape();
        for (String state : GiftBusinessGauge.STATES) {
            for (String kind : List.of("voucher", "code")) {
                for (String delivery : List.of("email", "handle")) {
                    String labels = "delivery=\"" + delivery + "\",kind=\"" + kind + "\",product=\"gift\",state=\""
                            + state + "\",unit=\"GBP\"";
                    assertTrue(text.contains("imani_product_items{" + labels + "} 0.0"), labels);
                    assertTrue(text.contains("imani_product_value{" + labels + "} 0.0"), labels);
                }
            }
        }
        assertTrue(text.contains("imani_business_metrics_poll_failures_total{source=\"payment-adapter\"} 0.0"));
        assertTrue(text.contains("imani_business_metrics_last_poll_seconds{source=\"payment-adapter\"} 0.0"));
    }

    @Test
    void sumsCountsAndMajorUnitValuesPerState() {
        given(
                row("email", null, Status.OWED, null, "GBP", 2, 5000, null),
                row("email", null, Status.DISCHARGED, null, "GBP", 1, 2500, null),
                row("handle", null, Status.DISCHARGED, GiftOutcome.CLAIMED, "GBP", 3, 3000, 0L),
                row("handle", null, Status.DISCHARGED, GiftOutcome.EXPIRED, "GBP", 1, 1000, null),
                row("handle", null, Status.DISCHARGED, GiftOutcome.RETURNED, "GBP", 1, 700, null),
                row("email", null, Status.REFUNDED, null, "GBP", 1, 1200, null),
                row("email", "code", Status.DISCHARGED, GiftOutcome.PARTLY_USED, "EUR", 1, 2000, 500L),
                row("email", "code", Status.DISCHARGED, GiftOutcome.EXPIRED, "EUR", 1, 1000, 400L),
                row("email", null, Status.DISCHARGED, null, "JPY", 1, 1500, null));
        gauge.poll();

        assertEquals(3, gauge.items("outstanding", "GBP", "voucher", "email"));
        assertEquals(75.0, gauge.value("outstanding", "GBP", "voucher", "email"), 1e-9);
        assertEquals(3, gauge.items("redeemed", "GBP", "voucher", "handle"));
        assertEquals(30.0, gauge.value("redeemed", "GBP", "voucher", "handle"), 1e-9);
        assertEquals(1, gauge.items("expired", "GBP", "voucher", "handle"));
        assertEquals(10.0, gauge.value("expired", "GBP", "voucher", "handle"), 1e-9);
        assertEquals(1, gauge.items("returned", "GBP", "voucher", "handle"));
        assertEquals(7.0, gauge.value("returned", "GBP", "voucher", "handle"), 1e-9);
        assertEquals(1, gauge.items("returned", "GBP", "voucher", "email"));
        assertEquals(12.0, gauge.value("returned", "GBP", "voucher", "email"), 1e-9);
        // A partly used code: one item outstanding, its value split between remaining and spent.
        assertEquals(1, gauge.items("outstanding", "EUR", "code", "email"));
        assertEquals(5.0, gauge.value("outstanding", "EUR", "code", "email"), 1e-9);
        // An expired code that was partly spent: the item is expired, the spent part redeemed.
        assertEquals(1, gauge.items("expired", "EUR", "code", "email"));
        assertEquals(4.0, gauge.value("expired", "EUR", "code", "email"), 1e-9);
        assertEquals(0, gauge.items("redeemed", "EUR", "code", "email"));
        assertEquals(15.0 + 6.0, gauge.value("redeemed", "EUR", "code", "email"), 1e-9);
        // JPY has no minor digits.
        assertEquals(1500.0, gauge.value("outstanding", "JPY", "voucher", "email"), 1e-9);
        assertEquals(NOW.getEpochSecond(), gauge.lastPollSeconds());
    }

    @Test
    void neverCountsAGiftTwice() {
        // Each purchase is one row in one group, so items summed over states equal rows, and value
        // summed over states equals the face value paid.
        given(
                row("email", "code", Status.DISCHARGED, GiftOutcome.PARTLY_USED, "GBP", 4, 8000, 3000L),
                row("handle", null, Status.DISCHARGED, GiftOutcome.CLAIMED, "GBP", 2, 4000, 0L),
                row("email", null, Status.REFUNDED, GiftOutcome.CLAIMED, "GBP", 1, 900, 0L),
                row("handle", null, Status.ISSUED, null, "GBP", 1, 100, null));
        gauge.poll();
        double items = 0;
        double value = 0;
        for (String s : GiftBusinessGauge.STATES) for (String k : GiftBusinessGauge.KINDS)
            for (String d : GiftBusinessGauge.DELIVERIES) {
                items += gauge.items(s, "GBP", k, d);
                value += gauge.value(s, "GBP", k, d);
            }
        assertEquals(8, items);
        assertEquals(130.0, value, 1e-9);
    }

    @Test
    void aFailedPollKeepsTheLastValuesAndCountsTheFailure() {
        given(row("email", null, Status.OWED, null, "GBP", 2, 5000, null));
        gauge.poll();
        when(purchases.summariseGifts(anyCollection())).thenThrow(new RuntimeException("db down"));
        gauge.poll();
        gauge.poll();
        assertEquals(2, gauge.items("outstanding", "GBP", "voucher", "email"));
        assertEquals(50.0, gauge.value("outstanding", "GBP", "voucher", "email"), 1e-9);
        assertTrue(scrape().contains("imani_business_metrics_poll_failures_total{source=\"payment-adapter\"} 2.0"));
        assertEquals(NOW.getEpochSecond(), gauge.lastPollSeconds());
    }

    @Test
    void aStateThatEmptiesFallsBackToZero() {
        given(row("email", null, Status.OWED, null, "GBP", 2, 5000, null));
        gauge.poll();
        given();
        gauge.poll();
        assertEquals(0, gauge.items("outstanding", "GBP", "voucher", "email"));
        assertEquals(0, gauge.value("outstanding", "GBP", "voucher", "email"));
    }

    @Test
    void collapsesUnitsBeyondFortyIntoOther() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            rows.add(row("email", null, Status.OWED, null, "X" + (char) ('A' + i / 26) + (char) ('A' + i % 26), 1, 100, null));
        }
        when(purchases.summariseGifts(anyCollection())).thenReturn(rows);
        gauge.poll();
        assertEquals(40, gauge.boundUnits().size());
        assertEquals(6, gauge.items("outstanding", "OTHER", "voucher", "email"));
    }

    @Test
    void scrapeTextIsValidAndCarriesNoIdentifiers() {
        given(row("email", "code", Status.DISCHARGED, GiftOutcome.PARTLY_USED, "gbp", 1, 2000, 500L));
        gauge.poll();
        String text = scrape();
        Pattern sample = Pattern.compile("^[a-zA-Z_:][a-zA-Z0-9_:]*(\\{([a-zA-Z_][a-zA-Z0-9_]*=\"[^\"]*\",?)*\\})? [-+0-9.eE]+$");
        Pattern labelNames = Pattern.compile("([a-z_]+)=\"");
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            if (!line.startsWith("imani_product_") && !line.startsWith("imani_business_metrics_")) continue;
            assertTrue(sample.matcher(line).matches(), line);
            var m = labelNames.matcher(line);
            while (m.find()) {
                assertTrue(List.of("product", "state", "unit", "kind", "delivery", "source").contains(m.group(1)), line);
            }
        }
        assertTrue(text.contains("# TYPE imani_product_items gauge"));
        assertTrue(text.contains("# TYPE imani_product_value gauge"));
        assertTrue(text.contains("# TYPE imani_business_metrics_poll_failures_total counter"));
        assertTrue(text.contains("unit=\"GBP\""));
        assertFalse(text.contains("unit=\"gbp\""));
    }

    // ---- the outcome report the purchase issuer sends

    private PurchaseController controller() {
        return new PurchaseController(purchases, mock(PurchaseDischargeService.class),
                new OperatorResolution(purchases, mock(PurchaseDischargeService.class), LivePaymentCheck.unconfigured()));
    }

    private StripePurchase gift(String delivery) {
        StripePurchase p = new StripePurchase();
        p.setDeliveryKind(delivery);
        p.setAmountMinor(2000L);
        p.setCurrency("gbp");
        p.setStatus(Status.DISCHARGED);
        return p;
    }

    @Test
    void recordsAnOutcomeOnTheGiftAndNeverMovesAFinalOneBack() {
        UUID draft = UUID.randomUUID();
        StripePurchase p = gift("email");
        when(purchases.findFirstByDraftId(draft)).thenReturn(Optional.of(p));
        var c = controller();

        assertEquals(HttpStatus.NO_CONTENT, c.giftOutcome(draft.toString(),
                new PurchaseController.GiftOutcomeRequest("partly_used", 1500L)).getStatusCode());
        assertEquals(GiftOutcome.PARTLY_USED, p.getGiftOutcome());
        assertEquals(1500L, p.getGiftRemainingMinor());

        assertEquals(HttpStatus.NO_CONTENT, c.giftOutcome(draft.toString(),
                new PurchaseController.GiftOutcomeRequest("CLAIMED", null)).getStatusCode());
        assertEquals(0L, p.getGiftRemainingMinor());

        assertEquals(HttpStatus.OK, c.giftOutcome(draft.toString(),
                new PurchaseController.GiftOutcomeRequest("EXPIRED", null)).getStatusCode());
        assertEquals(GiftOutcome.CLAIMED, p.getGiftOutcome());
    }

    @Test
    void refusesWhatIsNotAGiftOrNotAnOutcome() {
        UUID draft = UUID.randomUUID();
        when(purchases.findFirstByDraftId(draft)).thenReturn(Optional.of(gift("self")));
        var c = controller();
        assertEquals(HttpStatus.CONFLICT, c.giftOutcome(draft.toString(),
                new PurchaseController.GiftOutcomeRequest("CLAIMED", null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, c.giftOutcome("not-a-uuid",
                new PurchaseController.GiftOutcomeRequest("CLAIMED", null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, c.giftOutcome(draft.toString(),
                new PurchaseController.GiftOutcomeRequest("SPENT", null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, c.giftOutcome(draft.toString(),
                new PurchaseController.GiftOutcomeRequest("PARTLY_USED", null)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, c.giftOutcome(UUID.randomUUID().toString(),
                new PurchaseController.GiftOutcomeRequest("CLAIMED", null)).getStatusCode());
        verify(purchases, never()).save(any());
    }

    @Test
    void clampsARemainingBalanceToTheGiftsAmount() {
        UUID draft = UUID.randomUUID();
        StripePurchase p = gift("email");
        when(purchases.findFirstByDraftId(draft)).thenReturn(Optional.of(p));
        controller().giftOutcome(draft.toString(), new PurchaseController.GiftOutcomeRequest("PARTLY_USED", 99_999L));
        assertEquals(2000L, p.getGiftRemainingMinor());
    }
}
