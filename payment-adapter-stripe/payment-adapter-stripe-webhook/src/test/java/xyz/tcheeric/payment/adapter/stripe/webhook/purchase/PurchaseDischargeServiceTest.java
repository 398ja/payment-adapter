package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Closing a debt only on evidence.
 *
 * <p>The rule under test is {@code imani-woo} ADR 0009's: fulfilment is
 * confirmed, not asserted. Its sharpest edge is the third outcome — an
 * unreachable gateway must leave everything exactly as it was, because
 * treating "could not ask" as "did not happen" turns an outage into an
 * accusation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseDischargeServiceTest {

    private static final String EVENT = "evt_1";
    private static final String REQUEST = "0123456789abcdef0123456789abcdef";
    private static final String STALL = "b".repeat(64);
    private static final String BUYER = "a".repeat(64);
    private static final String SELF = "d".repeat(64);

    @Mock private StripePurchaseRepository purchases;
    @Mock private GatewayFulfilmentClient fulfilment;

    private PurchaseDischargeService service;
    private StripePurchase owed;

    @BeforeEach
    void setUp() {
        service = new PurchaseDischargeService(purchases, fulfilment);
        owed = new StripePurchase();
        owed.setEventId(EVENT);
        owed.setPaymentRequestId(REQUEST);
        owed.setStatus(StripePurchase.Status.OWED);
        owed.setRecipientPubkey(BUYER);
        owed.setAmountMinor(2500L);
        owed.setCurrency("gbp");
        when(purchases.findByEventId(EVENT)).thenReturn(Optional.of(owed));
        when(purchases.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** What gateway-core#131 says for a real payment: the verdict and the facts behind it. */
    private void gatewaySays(Fulfilment verdict, String issuerId) {
        gatewaySays(new GatewayFulfilmentClient.Answer(verdict, issuerId,
                verdict == Fulfilment.FULFILLED ? BUYER : null,
                verdict == Fulfilment.FULFILLED ? 2500L : null,
                verdict == Fulfilment.FULFILLED ? "GBP" : null,
                verdict == Fulfilment.FULFILLED ? null : "unpaid"));
    }

    private void gatewaySays(GatewayFulfilmentClient.Answer answer) {
        when(fulfilment.check(REQUEST)).thenReturn(answer);
    }

    @Test
    void aOneSatSendToSelfDoesNotDischarge() {
        // The imani-wallet#160 attack. Somebody who saw the request id sends 1
        // sat of the stall's coupons to THEMSELF, tagged with it. Even if a
        // gateway called that "fulfilled", it went to the wrong person for the
        // wrong amount, so the buyer's debt stands and somebody is told.
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, SELF, 1L, "GBP", null));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(1, owed.getAttempts());
    }

    @Test
    void refusesAnUnderpaymentToTheRightBuyer() {
        // Right person, not enough value. Still owed.
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2499L, "GBP", null));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
    }

    @Test
    void refusesAPaymentInAnotherCurrency() {
        // 2500 of some other unit is not 2500 pence.
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "EUR", null));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
    }

    @Test
    void refusesWhenTheStallOnTheRowDidNotIssue() {
        // The row knows its stall. An answer naming another one does not
        // close this debt, even when the caller names no issuer.
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, "c".repeat(64), BUYER, 2500L, "GBP", null));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", null));
    }

    @Test
    void doesNotTrustABareFulfilledFromAGatewayBeforeRegistration() {
        // Before imani-gateway-core#131 the answer was a bare boolean that a
        // 1-sat self-send could produce. With no facts to check, nothing is
        // discharged, but nobody is accused either: it stays owed, unverifiable.
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL));

        assertEquals(PurchaseDischargeService.Result.UNVERIFIABLE,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(0, owed.getAttempts());
    }

    @Test
    void registersAPurchaseTheListenerCouldNotThenAsksAgain() {
        // Recorded before registration existed, or while the gateway was down.
        // The gateway says "unregistered", so discharge registers the terms
        // and asks once more.
        owed.setStallPubkey(STALL);
        when(fulfilment.check(REQUEST)).thenReturn(
                new GatewayFulfilmentClient.Answer(Fulfilment.NOT_FULFILLED, null, null, 0L, null, "unregistered"),
                new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null));
        when(fulfilment.register(REQUEST, BUYER, STALL, 2500L, "GBP"))
                .thenReturn(GatewayFulfilmentClient.Registration.REGISTERED);

        assertEquals(PurchaseDischargeService.Result.DISCHARGED,
                service.discharge(EVENT, "voucher-1", STALL));
        verify(fulfilment).register(REQUEST, BUYER, STALL, 2500L, "GBP");
    }

    @Test
    void dischargesWhenTheGatewayConfirmsIssuance() {
        gatewaySays(Fulfilment.FULFILLED, STALL);

        assertEquals(PurchaseDischargeService.Result.DISCHARGED,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.DISCHARGED, owed.getStatus());
        assertEquals("voucher-1", owed.getVoucherId(), "the coupon, as evidence");
    }

    @Test
    void refusesADischargeTheGatewayCannotSee() {
        // The issuer claimed something that did not happen. The debt stands.
        gatewaySays(Fulfilment.NOT_FULFILLED, null);

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertNull(owed.getVoucherId());
    }

    @Test
    void changesNothingWhenTheGatewayCannotBeAsked() {
        // The one that matters. An outage must not discharge, must not refuse,
        // and must not count as a failed attempt against the issuer.
        gatewaySays(Fulfilment.UNKNOWN, null);

        assertEquals(PurchaseDischargeService.Result.UNVERIFIABLE,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(0, owed.getAttempts(), "an outage is not the issuer's failure");
        verify(purchases, never()).save(any());
    }

    @Test
    void refusesWhenAnotherStallAnsweredTheRequest() {
        // ADR 0009 checks WHO issued: a completed send from some other stall
        // does not discharge this stall's debt.
        gatewaySays(Fulfilment.FULFILLED, "c".repeat(64));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
    }

    @Test
    void isIdempotentSoARetriedDischargeIsNotASecondSale() {
        owed.setStatus(StripePurchase.Status.DISCHARGED);

        assertEquals(PurchaseDischargeService.Result.ALREADY_DISCHARGED,
                service.discharge(EVENT, "voucher-1", STALL));
        verify(fulfilment, never()).check(any());
    }

    @Test
    void reportsAnUnknownPurchaseRatherThanCreatingOne() {
        when(purchases.findByEventId("evt_missing")).thenReturn(Optional.empty());

        assertEquals(PurchaseDischargeService.Result.UNKNOWN_PURCHASE,
                service.discharge("evt_missing", "voucher-1", STALL));
    }

    @Test
    void recordsAFailureWithoutClosingAnything() {
        service.recordFailure(EVENT, "the gateway was unreachable", false, null);

        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(1, owed.getAttempts());
        assertEquals("the gateway was unreachable", owed.getLastFailure());
    }

    @Test
    void blocksWhenRetryingCannotHelp() {
        // A burnt credential. Not "given up on": a different state, needing a
        // person rather than a timer.
        service.recordFailure(EVENT, "the stall no longer authorises this issuer", true, null);

        assertEquals(StripePurchase.Status.BLOCKED, owed.getStatus());
    }

    @Test
    void leavesTheMintableQueueOnceACouponExists() {
        // The coupon printer, and the test that stops it coming back.
        //
        // A failure that names a voucher is value already created. Left OWED
        // the next pass reads it as unminted and mints again - one payment, a
        // coupon on every wake of the worker.
        service.recordFailure(EVENT, "issued but delivery failed", false, "voucher-1");

        assertEquals(StripePurchase.Status.ISSUED, owed.getStatus());
        assertEquals("voucher-1", owed.getVoucherId(), "the handle on value that exists");
    }

    @Test
    void anIssuedPurchaseCanStillBeDischarged() {
        // ISSUED is not terminal. The coupon exists and this is the call that
        // confirms it reached the buyer; only MINTING again is forbidden.
        owed.setStatus(StripePurchase.Status.ISSUED);
        gatewaySays(Fulfilment.FULFILLED, STALL);

        assertEquals(PurchaseDischargeService.Result.DISCHARGED,
                service.discharge(EVENT, "voucher-1", STALL));
    }

    @Test
    void neverReopensADischargedPurchase() {
        owed.setStatus(StripePurchase.Status.DISCHARGED);

        service.recordFailure(EVENT, "late failure report", true, null);

        assertEquals(StripePurchase.Status.DISCHARGED, owed.getStatus());
    }

    @Test
    void neverSendsAnIssuedPurchaseBackToTheMintableQueue() {
        // imani-wallet#96: a later report naming no coupon must not undo the
        // ISSUED that an earlier one set. OWED here is a second mint.
        owed.setStatus(StripePurchase.Status.ISSUED);
        owed.setVoucherId("voucher-1");

        service.recordFailure(EVENT, "a later, voucherless report", false, null);

        assertEquals(StripePurchase.Status.ISSUED, owed.getStatus());
        assertEquals("voucher-1", owed.getVoucherId());
    }

    @Test
    void releasesAClaimWhenNothingWasMinted() {
        // A failure before the mint returns the row to OWED so it is retried.
        owed.setStatus(StripePurchase.Status.ISSUING);
        owed.setClaimedUntil(java.time.Instant.now().plusSeconds(600));

        service.recordFailure(EVENT, "gateway unreachable", false, null);

        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertNull(owed.getClaimedUntil());
    }

    @Test
    void aLateReportCannotReleaseAClaimAnotherWorkerHolds() {
        // imani-wallet#96 review: worker A's claim lapsed, B re-claimed and is
        // minting, then A's delayed "nothing minted" arrives. Releasing to
        // OWED here would let a third worker mint alongside B.
        owed.setStatus(StripePurchase.Status.ISSUING);
        owed.setClaimedBy("worker-b");
        owed.setClaimedUntil(java.time.Instant.now().plusSeconds(600));

        service.recordFailure(EVENT, "late report from a", false, null, "worker-a", false);

        assertEquals(StripePurchase.Status.ISSUING, owed.getStatus());
        assertEquals("worker-b", owed.getClaimedBy());
    }

    @Test
    void anAmbiguousOutcomeLeavesTheClaimToLapse() {
        // The mint may have happened (timed out, 5xx). Handing the row back at
        // once would re-mint while that request could still be settling.
        owed.setStatus(StripePurchase.Status.ISSUING);
        owed.setClaimedBy("worker-a");
        java.time.Instant until = java.time.Instant.now().plusSeconds(600);
        owed.setClaimedUntil(until);

        service.recordFailure(EVENT, "gateway timed out", false, null, "worker-a", true);

        assertEquals(StripePurchase.Status.ISSUING, owed.getStatus());
        assertEquals(until, owed.getClaimedUntil());
    }

    @Test
    void claimIsTheDatabasesVerdict() {
        when(purchases.claim(eq(EVENT), eq("w1"), any(), any(),
                eq(StripePurchase.Status.OWED), eq(StripePurchase.Status.ISSUING))).thenReturn(1);
        when(purchases.claim(eq(EVENT), eq("w2"), any(), any(), any(), any())).thenReturn(0);

        assertEquals(PurchaseDischargeService.ClaimResult.CLAIMED, service.claim(EVENT, "w1"));
        assertEquals(PurchaseDischargeService.ClaimResult.NOT_CLAIMABLE, service.claim(EVENT, "w2"));
    }

    @Test
    void claimingAnUnknownPurchaseCreatesNothing() {
        when(purchases.findByEventId("evt_missing")).thenReturn(Optional.empty());

        assertEquals(PurchaseDischargeService.ClaimResult.UNKNOWN_PURCHASE,
                service.claim("evt_missing", "w1"));
        verify(purchases, never()).claim(any(), any(), any(), any(), any(), any());
    }
}
