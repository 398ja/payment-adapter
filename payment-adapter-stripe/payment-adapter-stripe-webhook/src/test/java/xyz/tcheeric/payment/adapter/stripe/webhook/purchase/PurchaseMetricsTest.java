package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;

/** imani-deploy#210: checkout, issuance and the paid-unissued gauges. */
class PurchaseMetricsTest {

    private static final String STALL = "b".repeat(64);
    private static final String BUYER = "a".repeat(64);

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private double count(String status, String reason) {
        return registry.find("imani.payments").tag("source", "card").tag("status", status).tag("reason", reason)
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    private PurchaseCheckoutController controller(ConnectedStripeAccountRepository accounts,
                                                  StripeCheckoutService checkout) {
        PurchaseCheckoutController controller = new PurchaseCheckoutController(accounts, checkout);
        ReflectionTestUtils.setField(controller, "maxAmountMinor", 1_000_000L);
        controller.setMeterRegistry(registry);
        return controller;
    }

    private ConnectedStripeAccountRepository stall(boolean chargesEnabled) {
        ConnectedStripeAccountRepository accounts = mock(ConnectedStripeAccountRepository.class);
        ConnectedStripeAccount account = new ConnectedStripeAccount();
        account.setChargesEnabled(chargesEnabled);
        account.setDefaultCurrency("gbp");
        account.setStripeAccountId("acct_1");
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.of(account));
        return accounts;
    }

    @Test
    void aCheckoutForAStallThatCannotChargeIsRefusedWithThatReason() {
        var response = controller(stall(false), mock(StripeCheckoutService.class))
                .start(new PurchaseCheckoutController.PurchaseRequest(STALL, 500L, BUYER));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(1.0, count("refused", "charges_disabled"));
        assertEquals(0.0, count("initiated", "none"));
    }

    @Test
    void aCreatedCheckoutIsCountedAsInitiated() {
        StripeCheckoutService checkout = mock(StripeCheckoutService.class);
        when(checkout.createPurchaseSession(anyString(), any(), anyString(), eq("gbp"), eq("acct_1"), any(), any()))
                .thenReturn(StripeCheckoutSession.builder().sessionUrl("https://checkout.example/s").build());

        controller(stall(true), checkout).start(new PurchaseCheckoutController.PurchaseRequest(STALL, 500L, BUYER));

        assertEquals(1.0, count("initiated", "none"));
        assertEquals(500.0, registry.get("imani.payment.amount.minor").tag("status", "initiated")
                .tag("currency", "GBP").counter().count());
    }

    @Test
    void aMalformedRequestIsRefusedAsInvalid() {
        controller(mock(ConnectedStripeAccountRepository.class), mock(StripeCheckoutService.class))
                .start(new PurchaseCheckoutController.PurchaseRequest(STALL, -1L, BUYER));
        assertEquals(1.0, count("refused", "invalid_request"));
    }

    private StripePurchase owed(StripePurchaseRepository purchases) {
        StripePurchase row = new StripePurchase();
        row.setEventId("evt_1");
        row.setPaymentRequestId("0123456789abcdef0123456789abcdef");
        row.setStatus(StripePurchase.Status.OWED);
        row.setRecipientPubkey(BUYER);
        row.setAmountMinor(2500L);
        row.setCurrency("gbp");
        row.setCreatedAt(Instant.now().minusSeconds(30));
        when(purchases.findByEventId("evt_1")).thenReturn(Optional.of(row));
        when(purchases.save(any())).thenAnswer(i -> i.getArgument(0));
        return row;
    }

    @Test
    void aConfirmedDischargeIsIssuedOnceWithItsTimeToIssue() {
        StripePurchaseRepository purchases = mock(StripePurchaseRepository.class);
        GatewayFulfilmentClient fulfilment = mock(GatewayFulfilmentClient.class);
        owed(purchases);
        String own = "e".repeat(64);
        when(fulfilment.publicKeyHex()).thenReturn(own);
        when(fulfilment.check(anyString())).thenReturn(new GatewayFulfilmentClient.Answer(
                Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null, own, java.util.List.of("v1")));
        PurchaseDischargeService service = new PurchaseDischargeService(purchases, fulfilment)
                .withMetrics(new PaymentMetrics(registry));

        assertEquals(PurchaseDischargeService.Result.DISCHARGED, service.discharge("evt_1", "v1", STALL));
        assertEquals(PurchaseDischargeService.Result.ALREADY_DISCHARGED, service.discharge("evt_1", "v1", STALL));

        assertEquals(1.0, count("issued", "none"), "a repeated discharge is not a second coupon");
        assertEquals(1L, registry.get("imani.payment.duration").tag("stage", "paid_to_issued").timer().count());
    }

    @Test
    void anIssueFailureIsCountedByShape() {
        StripePurchaseRepository purchases = mock(StripePurchaseRepository.class);
        owed(purchases);
        PurchaseDischargeService service =
                new PurchaseDischargeService(purchases, mock(GatewayFulfilmentClient.class))
                        .withMetrics(new PaymentMetrics(registry));

        service.recordFailure("evt_1", "mint said no", false, null);
        service.recordFailure("evt_1", "dm failed", false, "voucher-1");
        service.recordFailure("evt_1", "credential burnt", true, null);

        assertEquals(1.0, count("issue_failed", "mint_failed"));
        assertEquals(1.0, count("issue_failed", "delivery_failed"));
        assertEquals(1.0, count("issue_failed", "blocked"));
    }

    @Test
    void theGaugesReportCountAndOldestAgeAndHoldOnError() {
        StripePurchaseRepository purchases = mock(StripePurchaseRepository.class);
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        when(purchases.countByStatusIn(PaidUnissuedGauge.OPEN)).thenReturn(2L);
        when(purchases.findOldestCreatedAtByStatusIn(PaidUnissuedGauge.OPEN))
                .thenReturn(now.minusSeconds(900));
        PaidUnissuedGauge gauge = new PaidUnissuedGauge(purchases, registry, Clock.fixed(now, ZoneOffset.UTC));

        assertEquals(0.0, registry.get("imani_payments_paid_unissued").gauge().value(), "bound at zero before a poll");
        gauge.poll();
        assertEquals(2.0, registry.get("imani_payments_paid_unissued").gauge().value());
        assertEquals(900.0, registry.get("imani_payments_paid_unissued_oldest_seconds").gauge().value());

        when(purchases.countByStatusIn(any())).thenThrow(new IllegalStateException("db down"));
        gauge.poll();
        assertEquals(2.0, registry.get("imani_payments_paid_unissued").gauge().value(), "a failed poll is not a zero");
    }

    /**
     * Second check, S-3 (mutants 6, 7): "paid, not issued" is every status but DISCHARGED, BLOCKED
     * included. Pinned against the enum, not against OPEN itself.
     */
    @Test
    void openIsEveryStatusButDischargedAndRefunded() {
        // Review of #278: REFUNDED (money back before issue) is not "paid, holds nothing".
        EnumSet<StripePurchase.Status> expected = EnumSet.allOf(StripePurchase.Status.class);
        expected.remove(StripePurchase.Status.DISCHARGED);
        expected.remove(StripePurchase.Status.REFUNDED);
        assertEquals(expected, PaidUnissuedGauge.OPEN);
    }
}
