package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.AttentionReason;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseDischargeService.AttentionResult;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseDischargeService.ResolveAction;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseDischargeService.ResolveResult;

/**
 * payment-adapter#273: NEEDS_ATTENTION. A purchase nothing automatic can safely move on waits for a
 * person, with a reason, and is never handed back to a minting worker.
 */
class PurchaseAttentionTest {

    private static final String EVENT = "evt_1";

    private StripePurchaseRepository purchases;
    private PurchaseDischargeService service;
    private StripePurchase row;

    @BeforeEach
    void setUp() {
        purchases = mock(StripePurchaseRepository.class);
        service = new PurchaseDischargeService(purchases, mock(GatewayFulfilmentClient.class));
        row = new StripePurchase();
        row.setEventId(EVENT);
        row.setStatus(StripePurchase.Status.ISSUING);
        row.setClaimedBy("w1");
        row.setClaimedUntil(Instant.now().plusSeconds(600));
        row.setCurrency("gbp");
        when(purchases.findByEventId(EVENT)).thenReturn(Optional.of(row));
        when(purchases.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** gc's 409 UNRECORDED parks the row with its reason and releases the claim, never back to OWED. */
    @Test
    void anUnrecordedMintParksTheRowForAPerson() {
        assertEquals(AttentionResult.RECORDED,
                service.reportAttention(EVENT, AttentionReason.GATEWAY_UNRECORDED, "gc 409 UNRECORDED", "w1", null));

        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
        assertEquals(AttentionReason.GATEWAY_UNRECORDED, row.getAttentionReason());
        assertEquals("gc 409 UNRECORDED", row.getAttentionDetail());
        assertNull(row.getClaimedUntil());
    }

    /** Reporting the same thing twice is a no-op that still answers success: the issuer may retry. */
    @Test
    void aRepeatedReportIsIdempotentAndKeepsTheFirstReason() {
        service.reportAttention(EVENT, AttentionReason.GATEWAY_UNRECORDED, "first", "w1", null);
        Instant first = row.getAttentionAt();

        assertEquals(AttentionResult.ALREADY_RECORDED,
                service.reportAttention(EVENT, AttentionReason.DELIVERY_EXHAUSTED, "second", "w1", null));
        assertEquals(AttentionReason.GATEWAY_UNRECORDED, row.getAttentionReason());
        assertEquals("first", row.getAttentionDetail());
        assertEquals(first, row.getAttentionAt());
    }

    /** A worker whose lease lapsed cannot park a row another worker now holds. */
    @Test
    void aWorkerCannotParkARowAnotherWorkerHolds() {
        assertEquals(AttentionResult.NOT_ALLOWED,
                service.reportAttention(EVENT, AttentionReason.GATEWAY_UNRECORDED, "late", "w2", null));
        assertEquals(StripePurchase.Status.ISSUING, row.getStatus());
    }

    /** A discharged or refunded row is final; a report about it is refused, not applied. */
    @Test
    void closedRowsAreNotParked() {
        row.setStatus(StripePurchase.Status.DISCHARGED);
        assertEquals(AttentionResult.NOT_ALLOWED,
                service.reportAttention(EVENT, AttentionReason.DELIVERY_EXHAUSTED, "x", null, "v1"));
        row.setStatus(StripePurchase.Status.REFUNDED);
        assertEquals(AttentionResult.NOT_ALLOWED,
                service.reportAttention(EVENT, AttentionReason.DELIVERY_EXHAUSTED, "x", null, "v1"));
    }

    /** Refunded/disputed are derived from Stripe's facts; the issuer cannot report them. */
    @Test
    void derivedReasonsCannotBeReported() {
        assertEquals(AttentionResult.INVALID_REASON,
                service.reportAttention(EVENT, AttentionReason.DISPUTED, "x", "w1", null));
        assertEquals(AttentionResult.INVALID_REASON,
                service.reportAttention(EVENT, AttentionReason.REFUNDED_AFTER_ISSUE, "x", "w1", null));
        assertEquals(StripePurchase.Status.ISSUING, row.getStatus());
    }

    /** Delivery exhausted names the coupon that exists, which is kept as the only handle on it. */
    @Test
    void anExhaustedDeliveryKeepsTheVoucher() {
        row.setStatus(StripePurchase.Status.ISSUED);
        row.setClaimedBy(null);
        service.reportAttention(EVENT, AttentionReason.DELIVERY_EXHAUSTED, "10 tries", null, "v1");

        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
        assertEquals("v1", row.getVoucherId());
    }

    /** Unknown purchase: 404 at the controller. */
    @Test
    void anUnknownPurchaseIsReported() {
        when(purchases.findByEventId("nope")).thenReturn(Optional.empty());
        assertEquals(AttentionResult.UNKNOWN_PURCHASE,
                service.reportAttention("nope", AttentionReason.GATEWAY_UNRECORDED, null, null, null));
    }

    /**
     * A lease that lapsed too many times means a worker keeps dying mid-mint: the claim refuses it and
     * parks it as stale_issuing rather than minting again.
     */
    @Test
    void aRowWhoseLeaseLapsedTooOftenIsParkedInsteadOfClaimed() {
        service = new PurchaseDischargeService(purchases, mock(GatewayFulfilmentClient.class),
                java.time.Duration.ofMinutes(10), 3);
        row.setClaimedUntil(Instant.now().minusSeconds(1));
        row.setLeaseExpiries(3);

        assertEquals(PurchaseDischargeService.ClaimResult.NOT_CLAIMABLE, service.claim(EVENT, "w9"));
        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
        assertEquals(AttentionReason.STALE_ISSUING, row.getAttentionReason());
        verify(purchases, never()).claim(anyString(), anyString(), any(), any(), any(), any());
    }

    /** Below the limit, a lapsed lease is claimed as before. */
    @Test
    void aLapsedLeaseBelowTheLimitIsStillClaimable() {
        service = new PurchaseDischargeService(purchases, mock(GatewayFulfilmentClient.class),
                java.time.Duration.ofMinutes(10), 3);
        row.setClaimedUntil(Instant.now().minusSeconds(1));
        row.setLeaseExpiries(2);
        when(purchases.claim(eq(EVENT), anyString(), any(), any(), any(), any())).thenReturn(1);

        assertEquals(PurchaseDischargeService.ClaimResult.CLAIMED, service.claim(EVENT, "w9"));
    }

    /** A failure report never moves a parked row back into the mintable queue. */
    @Test
    void aFailureReportLeavesAParkedRowParked() {
        row.setStatus(StripePurchase.Status.NEEDS_ATTENTION);
        row.setAttentionReason(AttentionReason.GATEWAY_UNRECORDED);

        service.recordFailure(EVENT, "retry?", false, null, "w1", false);

        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
    }

    /** Review of #280: a late report naming the coupon keeps it on the parked row, status unchanged. */
    @Test
    void aLateReportNamingACouponKeepsItOnTheParkedRow() {
        park(AttentionReason.GATEWAY_UNRECORDED, null);

        service.recordFailure(EVENT, "delivery failed", false, "v7", "w1", false);

        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
        assertEquals("v7", row.getVoucherId());
    }

    /** An operator retry of a row with no coupon puts it back in the queue and clears the reason. */
    @Test
    void anOperatorRetryOfANeverMintedRowReturnsItToOwed() {
        park(AttentionReason.STALE_ISSUING, null);

        assertEquals(ResolveResult.RESOLVED, service.resolve(EVENT, ResolveAction.RETRY, null, "ops", "checked gc"));
        assertEquals(StripePurchase.Status.OWED, row.getStatus());
        assertNull(row.getAttentionReason());
        assertEquals(0, row.getLeaseExpiries());
        assertEquals("ops", row.getResolvedBy());
        assertEquals("checked gc", row.getResolutionNote());
    }

    /** A retry of a row whose coupon exists re-enters ISSUED (delivery), never OWED (a second mint). */
    @Test
    void anOperatorRetryOfAMintedRowReturnsItToIssued() {
        park(AttentionReason.DELIVERY_EXHAUSTED, "v1");

        service.resolve(EVENT, ResolveAction.RETRY, null, "ops", null);
        assertEquals(StripePurchase.Status.ISSUED, row.getStatus());
    }

    /**
     * Review of #280: a parked row with no coupon that is refunded meanwhile stays parked (the
     * listener only moves OWED/ISSUING/BLOCKED to REFUNDED). An operator retry must not hand it to
     * the mintable queue: a refund before issue never mints (#267). It lands where the automatic
     * flow would have put it, REFUNDED.
     */
    @Test
    void anOperatorRetryOfARefundedParkedRowNeverReturnsItToOwed() {
        park(AttentionReason.STALE_ISSUING, null);
        row.setRefundedAmountMinor(500L);
        row.setReversedAt(Instant.now());

        assertEquals(ResolveResult.RESOLVED, service.resolve(EVENT, ResolveAction.RETRY, null, "ops", "retry"));
        assertEquals(StripePurchase.Status.REFUNDED, row.getStatus());
        assertEquals("ops", row.getResolvedBy());
        assertEquals(ResolveResult.ALREADY_RESOLVED, service.resolve(EVENT, ResolveAction.RETRY, null, "ops", null));
    }

    /** gateway_unrecorded means the mint state is unknown: retrying could mint twice, so it is refused. */
    @Test
    void anUnrecordedMintIsNeverRetried() {
        park(AttentionReason.GATEWAY_UNRECORDED, null);

        assertEquals(ResolveResult.NOT_ALLOWED, service.resolve(EVENT, ResolveAction.RETRY, null, "ops", null));
        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
    }

    /**
     * #219 review F5: a gateway_unrecorded row refunded at Stripe could never leave
     * NEEDS_ATTENTION (retry refused, acknowledge changes no status), so its alert fired forever.
     * Retry closes it as REFUNDED, which never mints.
     */
    @Test
    void aRefundedUnrecordedRowCanBeClosedAsRefunded() {
        park(AttentionReason.GATEWAY_UNRECORDED, null);
        row.setRefundedAmountMinor(500L);
        row.setReversedAt(Instant.now());

        assertEquals(ResolveResult.RESOLVED, service.resolve(EVENT, ResolveAction.RETRY, null, "ops", "refunded"));
        assertEquals(StripePurchase.Status.REFUNDED, row.getStatus());
        assertNull(row.getAttentionReason());
    }

    /** Review of #296: a recorded dispute stops a retry in the resolve itself, whatever the live read said. */
    @Test
    void aRetryOfADisputedRowIsRefused() {
        park(AttentionReason.STALE_ISSUING, null);
        row.setDisputed(true);

        assertEquals(ResolveResult.NOT_ALLOWED, service.resolve(EVENT, ResolveAction.RETRY, null, "ops", null));
        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
    }

    /** Review of #296: a recorded refund on a minted row stops a retry: the coupon is not delivered. */
    @Test
    void aRetryOfARefundedMintedRowIsRefused() {
        park(AttentionReason.DELIVERY_EXHAUSTED, "v1");
        row.setRefundedAmountMinor(200L);

        assertEquals(ResolveResult.NOT_ALLOWED, service.resolve(EVENT, ResolveAction.RETRY, null, "ops", null));
        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
    }

    /** ...while discharging it by hand stays possible: that records a delivery, it mints nothing. */
    @Test
    void aRefundedMintedRowCanStillBeDischarged() {
        park(AttentionReason.DELIVERY_EXHAUSTED, "v1");
        row.setRefundedAmountMinor(200L);

        assertEquals(ResolveResult.RESOLVED, service.resolve(EVENT, ResolveAction.DISCHARGED, null, "ops", null));
        assertEquals(StripePurchase.Status.DISCHARGED, row.getStatus());
    }

    /** ...but not when a coupon is named: that row is a refund after issue, for a person. */
    @Test
    void aRefundedUnrecordedRowWithACouponIsStillNotRetried() {
        park(AttentionReason.GATEWAY_UNRECORDED, "v1");
        row.setRefundedAmountMinor(500L);
        row.setReversedAt(Instant.now());

        assertEquals(ResolveResult.NOT_ALLOWED, service.resolve(EVENT, ResolveAction.RETRY, null, "ops", null));
        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
    }

    /** An operator who delivered by hand closes the row, naming the coupon. */
    @Test
    void anOperatorDischargeNeedsAVoucher() {
        park(AttentionReason.GATEWAY_UNRECORDED, null);

        assertEquals(ResolveResult.INVALID, service.resolve(EVENT, ResolveAction.DISCHARGED, null, "ops", null));
        assertEquals(ResolveResult.RESOLVED, service.resolve(EVENT, ResolveAction.DISCHARGED, "v9", "ops", "by hand"));
        assertEquals(StripePurchase.Status.DISCHARGED, row.getStatus());
        assertEquals("v9", row.getVoucherId());
    }

    /**
     * Review of #280: the row's voucher id is the only handle on value already created. An operator
     * naming a DIFFERENT coupon must not erase it from the trail; the same id (or none) is fine.
     */
    @Test
    void anOperatorDischargeCannotReplaceTheRecordedVoucher() {
        park(AttentionReason.DELIVERY_EXHAUSTED, "v1");

        assertEquals(ResolveResult.INVALID, service.resolve(EVENT, ResolveAction.DISCHARGED, "v2", "ops", null));
        assertEquals(StripePurchase.Status.NEEDS_ATTENTION, row.getStatus());
        assertEquals("v1", row.getVoucherId());
        assertEquals(ResolveResult.RESOLVED, service.resolve(EVENT, ResolveAction.DISCHARGED, "v1", "ops", null));
        assertEquals("v1", row.getVoucherId());
    }

    /** Repeating a resolution that already took effect answers success and changes nothing. */
    @Test
    void aRepeatedResolveIsIdempotent() {
        park(AttentionReason.DELIVERY_EXHAUSTED, "v1");
        service.resolve(EVENT, ResolveAction.DISCHARGED, null, "ops", null);

        assertEquals(ResolveResult.ALREADY_RESOLVED, service.resolve(EVENT, ResolveAction.DISCHARGED, null, "ops", null));
        assertEquals(StripePurchase.Status.DISCHARGED, row.getStatus());
    }

    /** Retry or discharge of a row nobody parked is refused: /resolve is not a back door. */
    @Test
    void resolveOnlyMovesParkedRows() {
        row.setStatus(StripePurchase.Status.OWED);
        assertEquals(ResolveResult.NOT_ALLOWED, service.resolve(EVENT, ResolveAction.DISCHARGED, "v1", "ops", null));
        assertEquals(StripePurchase.Status.OWED, row.getStatus());
    }

    /** Refund and dispute facts after issue are acknowledged, not resolved: the status never moves. */
    @Test
    void acknowledgingAReversalRecordsItWithoutChangingStatus() {
        row.setStatus(StripePurchase.Status.DISCHARGED);
        row.setVoucherId("v1");
        row.setDisputed(true);
        row.setReversedAt(Instant.now());

        assertEquals(ResolveResult.RESOLVED, service.resolve(EVENT, ResolveAction.ACKNOWLEDGE, null, "ops", "seen"));
        assertEquals(StripePurchase.Status.DISCHARGED, row.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(row.getReversalAcknowledgedAt());
        assertEquals(ResolveResult.ALREADY_RESOLVED, service.resolve(EVENT, ResolveAction.ACKNOWLEDGE, null, "ops", null));
    }

    /** Nothing to acknowledge is refused. */
    @Test
    void acknowledgingARowWithNoReversalIsRefused() {
        row.setStatus(StripePurchase.Status.DISCHARGED);
        assertEquals(ResolveResult.NOT_ALLOWED, service.resolve(EVENT, ResolveAction.ACKNOWLEDGE, null, "ops", null));
    }

    /** The controller maps every outcome to a status a caller can act on, and parses reasons by wire name. */
    @Test
    void theControllerSpeaksTheWireContract() {
        PurchaseDischargeService discharges = mock(PurchaseDischargeService.class);
        PurchaseController controller = controllerOver(discharges);
        when(discharges.reportAttention(EVENT, AttentionReason.GATEWAY_UNRECORDED, "d", "w1", null))
                .thenReturn(AttentionResult.RECORDED);
        when(discharges.reportAttention(EVENT, AttentionReason.DELIVERY_EXHAUSTED, null, null, "v1"))
                .thenReturn(AttentionResult.ALREADY_RECORDED);
        when(discharges.reportAttention(eq("gone"), any(), any(), any(), any()))
                .thenReturn(AttentionResult.UNKNOWN_PURCHASE);
        when(discharges.reportAttention(eq("held"), any(), any(), any(), any()))
                .thenReturn(AttentionResult.NOT_ALLOWED);

        assertEquals(HttpStatus.OK, controller.attention(EVENT,
                new PurchaseController.AttentionRequest("gateway_unrecorded", "d", "w1", null)).getStatusCode());
        assertEquals(HttpStatus.OK, controller.attention(EVENT,
                new PurchaseController.AttentionRequest("delivery_exhausted", null, null, "v1")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.attention("gone",
                new PurchaseController.AttentionRequest("stale_issuing", null, null, null)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.attention("held",
                new PurchaseController.AttentionRequest("stale_issuing", null, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.attention(EVENT,
                new PurchaseController.AttentionRequest("bogus", null, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.attention(EVENT, null).getStatusCode());

        when(discharges.resolve(EVENT, ResolveAction.RETRY, null, "ops", null)).thenReturn(ResolveResult.RESOLVED);
        when(discharges.resolve(EVENT, ResolveAction.DISCHARGED, null, "ops", null)).thenReturn(ResolveResult.INVALID);
        when(discharges.resolve(EVENT, ResolveAction.ACKNOWLEDGE, null, "ops", null)).thenReturn(ResolveResult.NOT_ALLOWED);
        assertEquals(HttpStatus.OK, controller.resolve(EVENT,
                new PurchaseController.ResolveRequest("retry", null, "ops", null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.resolve(EVENT,
                new PurchaseController.ResolveRequest("discharged", null, "ops", null)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.resolve(EVENT,
                new PurchaseController.ResolveRequest("acknowledge", null, "ops", null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.resolve(EVENT,
                new PurchaseController.ResolveRequest("refund", null, "ops", null)).getStatusCode());
    }

    /** The operator list carries the wire reason. */
    @Test
    void theControllerListsParkedRowsWithTheirReason() {
        PurchaseController controller = controllerOver(mock(PurchaseDischargeService.class));
        park(AttentionReason.GATEWAY_UNRECORDED, null);
        when(purchases.findByStatusOrderByCreatedAtAsc(StripePurchase.Status.NEEDS_ATTENTION)).thenReturn(List.of(row));

        List<PurchaseController.AttentionPurchase> parked = controller.attentionList();
        assertEquals(1, parked.size());
        assertEquals("gateway_unrecorded", parked.get(0).reason());
    }

    /** One series per reason, bound at zero; a failed poll holds the last values (#268). */
    @Test
    void theGaugeReportsEveryReasonAndNeverThrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        java.util.List<Object[]> grouped = new java.util.ArrayList<>();
        grouped.add(new Object[] {AttentionReason.GATEWAY_UNRECORDED, 2L});
        when(purchases.countByAttentionReason(StripePurchase.Status.NEEDS_ATTENTION)).thenReturn(grouped);
        when(purchases.countUnacknowledgedRefundsAfterIssue()).thenReturn(1L);
        when(purchases.countUnacknowledgedDisputes()).thenReturn(4L);
        NeedsAttentionGauge gauge = new NeedsAttentionGauge(purchases, registry);

        for (AttentionReason reason : AttentionReason.values()) {
            assertEquals(0.0, value(registry, reason.wire()), reason + " bound at zero");
        }
        gauge.poll();
        assertEquals(2.0, value(registry, "gateway_unrecorded"));
        assertEquals(0.0, value(registry, "stale_issuing"));
        assertEquals(1.0, value(registry, "refunded_after_issue"));
        assertEquals(4.0, value(registry, "disputed"));

        when(purchases.countByAttentionReason(any())).thenThrow(new IllegalStateException("db down"));
        gauge.poll();
        assertEquals(2.0, value(registry, "gateway_unrecorded"), "a failed poll is not a zero");
    }

    /** A write lost to a concurrent writer (@Version) is retryable: 503, never a false 200. */
    @Test
    void aLostOptimisticLockIsRetryable() {
        PurchaseDischargeService discharges = mock(PurchaseDischargeService.class);
        PurchaseController controller = controllerOver(discharges);
        org.springframework.orm.ObjectOptimisticLockingFailureException lost =
                new org.springframework.orm.ObjectOptimisticLockingFailureException(StripePurchase.class, EVENT);
        when(discharges.reportAttention(any(), any(), any(), any(), any())).thenThrow(lost);
        when(discharges.resolve(any(), any(), any(), any(), any())).thenThrow(lost);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.attention(EVENT,
                new PurchaseController.AttentionRequest("gateway_unrecorded", null, null, null)).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.resolve(EVENT,
                new PurchaseController.ResolveRequest("retry", null, "ops", null)).getStatusCode());
    }

    /** Decision: a parked row is still paid and unissued, so paid_unissued keeps counting it. */
    @Test
    void paidUnissuedStillCountsParkedRows() {
        org.junit.jupiter.api.Assertions.assertTrue(
                PaidUnissuedGauge.OPEN.contains(StripePurchase.Status.NEEDS_ATTENTION));
    }

    private static double value(SimpleMeterRegistry registry, String reason) {
        return registry.get("imani_payments_card_needs_attention").tag("reason", reason).gauge().value();
    }

    private PurchaseController controllerOver(PurchaseDischargeService discharges) {
        return new PurchaseController(purchases, discharges,
                new OperatorResolution(purchases, discharges, LivePaymentCheck.unconfigured()));
    }

    private void park(AttentionReason reason, String voucherId) {
        row.setStatus(StripePurchase.Status.NEEDS_ATTENTION);
        row.setAttentionReason(reason);
        row.setVoucherId(voucherId);
        row.setClaimedUntil(null);
        row.setLeaseExpiries(5);
    }
}
