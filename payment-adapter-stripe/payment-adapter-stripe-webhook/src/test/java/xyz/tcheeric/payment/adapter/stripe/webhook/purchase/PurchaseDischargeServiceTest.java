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
    /** This service's own registering key. */
    private static final String OWN = "e".repeat(64);
    private static final String SQUATTER = "f".repeat(64);

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
        // Recorded before any rollover a test configures (#288 review): an old key only vouches for
        // rows that already existed. Production rows always carry it (created_at NOT NULL).
        owed.setCreatedAt(java.time.Instant.now().minusSeconds(3600));
        when(purchases.findByEventId(EVENT)).thenReturn(Optional.of(owed));
        when(purchases.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fulfilment.publicKeyHex()).thenReturn(OWN);
    }

    /** What gateway-core#131 says for a real payment: the verdict and the facts behind it. */
    private void gatewaySays(Fulfilment verdict, String issuerId) {
        gatewaySays(new GatewayFulfilmentClient.Answer(verdict, issuerId,
                verdict == Fulfilment.FULFILLED ? BUYER : null,
                verdict == Fulfilment.FULFILLED ? 2500L : null,
                verdict == Fulfilment.FULFILLED ? "GBP" : null,
                verdict == Fulfilment.FULFILLED ? null : "unpaid",
                OWN,
                verdict == Fulfilment.FULFILLED ? java.util.List.of("voucher-1") : java.util.List.of()));
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
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, SELF, 1L, "GBP", null, OWN, java.util.List.of("voucher-1")));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(1, owed.getAttempts());
    }

    @Test
    void refusesAnUnderpaymentToTheRightBuyer() {
        // Right person, not enough value. Still owed.
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2499L, "GBP", null, OWN, java.util.List.of("voucher-1")));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
    }

    @Test
    void refusesAPaymentInAnotherCurrency() {
        // 2500 of some other unit is not 2500 pence.
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "EUR", null, OWN, java.util.List.of("voucher-1")));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
    }

    @Test
    void refusesWhenTheStallOnTheRowDidNotIssue() {
        // The row knows its stall. An answer naming another one does not
        // close this debt, even when the caller names no issuer.
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, "c".repeat(64), BUYER, 2500L, "GBP", null, OWN, java.util.List.of("voucher-1")));

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
                new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null, OWN,
                        java.util.List.of("voucher-1")));
        when(fulfilment.register(REQUEST, BUYER, STALL, 2500L, "GBP"))
                .thenReturn(GatewayFulfilmentClient.Registration.REGISTERED);

        assertEquals(PurchaseDischargeService.Result.DISCHARGED,
                service.discharge(EVENT, "voucher-1", STALL));
        verify(fulfilment).register(REQUEST, BUYER, STALL, 2500L, "GBP");
    }

    @Test
    void aRequestASquatterRegisteredFirstDoesNotDischarge() {
        // Somebody who saw the request id registered it first, naming this
        // purchase's own buyer, stall, amount and unit, so our PUT came back
        // 200 "already registered" and every fact in the answer matches. The
        // terms are still not ours: creatorPubkey names the squatter
        // (imani-gateway-core#131, fb2b76a). The debt stands.
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(
                Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null, SQUATTER, java.util.List.of("voucher-1")));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(1, owed.getAttempts());
    }

    @Test
    void anAnswerThatDoesNotNameItsRegistrantIsUnverifiable() {
        // A core with registration but before creatorPubkey cannot say whose
        // terms these are. Not confirmed, but nobody is accused either.
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null));

        assertEquals(PurchaseDischargeService.Result.UNVERIFIABLE,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(0, owed.getAttempts());
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

    @Test
    void theFullAmountSentToSomebodyElseDoesNotDischarge() {
        // The squatting case, isolated. Every fact is right except who was
        // paid: the full 2500 GBP of the right stall's coupons went to SELF,
        // not the buyer. Only the recipient check stands between this and a
        // discharge, so this test fails if that check is removed.
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, SELF, 2500L, "GBP", null, OWN, java.util.List.of("voucher-1")));

        assertEquals(PurchaseDischargeService.Result.REFUSED,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(1, owed.getAttempts());
    }

    @Test
    void aLateRegistrationThatCouldNotBeMadeIsUnverifiableNotRefused() {
        // The gateway says "unregistered" and our PUT to fix that times out or
        // gets a 5xx. Nothing was learned about the issuer, so the issuer must
        // not be blamed and no attempt counted: try again later.
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.NOT_FULFILLED, null, null, 0L, null, "unregistered"));
        when(fulfilment.register(REQUEST, BUYER, STALL, 2500L, "GBP"))
                .thenReturn(GatewayFulfilmentClient.Registration.FAILED);

        assertEquals(PurchaseDischargeService.Result.UNVERIFIABLE,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(0, owed.getAttempts());
    }

    @Test
    void aGatewayWithoutRegistrationIsUnverifiableNotRefused() {
        // Same, against a core older than #131 whose PUT answers 404/405.
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.NOT_FULFILLED, null, null, 0L, null, "unregistered"));
        when(fulfilment.register(REQUEST, BUYER, STALL, 2500L, "GBP"))
                .thenReturn(GatewayFulfilmentClient.Registration.UNSUPPORTED);

        assertEquals(PurchaseDischargeService.Result.UNVERIFIABLE,
                service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(0, owed.getAttempts());
    }

    @Test
    void theSweepDischargesAnIssuedRowOnceTheGatewayCanConfirmIt() {
        // PA-2. The issuer's one discharge got 503 (old core, outage), so its
        // failure report parked the row in ISSUED, where nothing retried it.
        // The sweep asks again; once the gateway can confirm, the debt closes
        // without anyone minting a second coupon.
        owed.setStatus(StripePurchase.Status.ISSUED);
        owed.setVoucherId("voucher-1");
        owed.setStallPubkey(STALL);
        when(purchases.findIssuedDueForRecheck(eq(StripePurchase.Status.ISSUED), any(), any(), any()))
                .thenReturn(java.util.List.of(owed));
        gatewaySays(Fulfilment.FULFILLED, STALL);

        assertEquals(1, service.recheckIssued(java.time.Instant.now()));
        assertEquals(StripePurchase.Status.DISCHARGED, owed.getStatus());
        assertEquals("voucher-1", owed.getVoucherId());
    }

    @Test
    void theSweepBacksOffARowItStillCannotVerify() {
        // Still unverifiable: the row stays ISSUED (never back to OWED, which
        // would mint again) but its clock moves on, so the next sweep looks at
        // other rows first instead of hammering the same batch.
        owed.setStatus(StripePurchase.Status.ISSUED);
        owed.setVoucherId("voucher-1");
        java.time.Instant before = java.time.Instant.parse("2026-01-01T00:00:00Z");
        owed.setUpdatedAt(before);
        when(purchases.findIssuedDueForRecheck(eq(StripePurchase.Status.ISSUED), any(), any(), any()))
                .thenReturn(java.util.List.of(owed));
        gatewaySays(Fulfilment.UNKNOWN, null);

        assertEquals(0, service.recheckIssued(java.time.Instant.now()));
        assertEquals(StripePurchase.Status.ISSUED, owed.getStatus());
        assertEquals(0, owed.getAttempts(), "an outage is not the issuer's failure");
        org.junit.jupiter.api.Assertions.assertTrue(owed.getUpdatedAt().isAfter(before));
    }

    @Test
    void theSweepNeverSavesItsOwnCopyOverARowThatChangedMeanwhile() {
        // Second check of #278: the sweep read the row as ISSUED; by the time discharge() answers,
        // a refund made it REFUNDED (fresh copy). Backing off must re-read, not save the stale copy.
        StripePurchase swept = new StripePurchase();
        swept.setEventId(EVENT);
        swept.setPaymentRequestId(REQUEST);
        swept.setStatus(StripePurchase.Status.ISSUED);
        swept.setVoucherId("voucher-1");
        owed.setStatus(StripePurchase.Status.REFUNDED);
        owed.setVoucherId("voucher-1");
        when(purchases.findIssuedDueForRecheck(eq(StripePurchase.Status.ISSUED), any(), any(), any()))
                .thenReturn(java.util.List.of(swept));

        assertEquals(0, service.recheckIssued(java.time.Instant.now()));

        verify(purchases, never()).save(org.mockito.ArgumentMatchers.same(swept));
        verify(purchases, never()).save(org.mockito.ArgumentMatchers.same(owed));
        assertEquals(StripePurchase.Status.REFUNDED, owed.getStatus());
    }

    @Test
    void aRefundedRowIsNeverDischargedAndRecordsTheLateVoucher() {
        // payment-adapter#267: refunded while a mint was in flight. The coupon that landed has no
        // money behind it, so the row must not read as served; its id is recorded for a person.
        owed.setStatus(StripePurchase.Status.REFUNDED);

        assertEquals(PurchaseDischargeService.Result.REFUNDED, service.discharge(EVENT, "voucher-late", STALL));

        assertEquals(StripePurchase.Status.REFUNDED, owed.getStatus());
        assertEquals("voucher-late", owed.getVoucherId());
        verify(fulfilment, never()).check(any());
    }

    @Test
    void aFailureReportNeverSendsARefundedRowBackToOwed() {
        // REFUNDED is terminal: back to OWED would mint for a refunded payment (S12).
        owed.setStatus(StripePurchase.Status.REFUNDED);

        service.recordFailure(EVENT, "mint refused", false, null);

        assertEquals(StripePurchase.Status.REFUNDED, owed.getStatus());
    }

    // imani-wallet#287 (U4): core counted a coupon for this request, but not the one this row
    // was issued. Another coupon of the same stall, buyer and face must not close this debt.
    @Test
    void aVerdictThatDoesNotCountTheRowsCouponIsRefused() {
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null, OWN,
                java.util.List.of("voucher-other")));

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, "voucher-1", STALL));

        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(1, owed.getAttempts());
    }

    // imani-wallet#287 (U4): an ISSUED row pins its OWN coupon, not whatever the caller names.
    @Test
    void anIssuedRowPinsItsOwnCouponOverTheCallersClaim() {
        owed.setStatus(StripePurchase.Status.ISSUED);
        owed.setVoucherId("voucher-1");
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null, OWN,
                java.util.List.of("voucher-2")));

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, "voucher-2", STALL));
        assertEquals(StripePurchase.Status.ISSUED, owed.getStatus());
    }

    // imani-wallet#287 (U4): a core from before U1 does not name the counted coupons. That is
    // not an accusation: UNVERIFIABLE, no attempt counted, nothing saved.
    @Test
    void aVerdictWithoutVoucherIdsIsUnverifiableAndCountsNoAttempt() {
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null, OWN));

        assertEquals(PurchaseDischargeService.Result.UNVERIFIABLE, service.discharge(EVENT, "voucher-1", STALL));

        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        assertEquals(0, owed.getAttempts());
        verify(purchases, never()).save(any());
    }

    // imani-wallet#287 (U4): the sweep keeps an ISSUED row open while core does not yet count
    // its coupon, and discharges it exactly once the verdict names it.
    @Test
    void theSweepDischargesOnceTheVerdictIncludesTheRowsCoupon() {
        owed.setStatus(StripePurchase.Status.ISSUED);
        owed.setVoucherId("voucher-1");
        owed.setStallPubkey(STALL);
        when(purchases.findIssuedDueForRecheck(eq(StripePurchase.Status.ISSUED), any(), any(), any()))
                .thenReturn(java.util.List.of(owed));
        when(fulfilment.check(REQUEST)).thenReturn(
                new GatewayFulfilmentClient.Answer(Fulfilment.NOT_FULFILLED, STALL, null, 0L, "GBP", "unpaid", OWN,
                        java.util.List.of()),
                new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null, OWN,
                        java.util.List.of("voucher-1")));

        assertEquals(0, service.recheckIssued(java.time.Instant.now()));
        assertEquals(StripePurchase.Status.ISSUED, owed.getStatus());

        assertEquals(1, service.recheckIssued(java.time.Instant.now()));
        assertEquals(StripePurchase.Status.DISCHARGED, owed.getStatus());
        assertEquals("voucher-1", owed.getVoucherId());
    }

    // imani-wallet#289 (E4): when the gateway does not yet count a delivered coupon, the row
    // says the voucher was delivered, so nobody reads it as "the buyer has nothing".
    @Test
    void anUnconfirmedDischargeSaysTheVoucherWasDelivered() {
        gatewaySays(Fulfilment.NOT_FULFILLED, null);

        service.discharge(EVENT, "voucher-1", STALL);

        org.junit.jupiter.api.Assertions.assertTrue(owed.getLastFailure().contains("voucher-1 delivered"),
                owed.getLastFailure());
        org.junit.jupiter.api.Assertions.assertTrue(owed.getLastFailure().contains("not yet confirmed"),
                owed.getLastFailure());
    }

    // imani-wallet#287 (U4 review): core counts both the row's coupon and another one the caller
    // names. The discharge closes, but the row keeps ITS coupon: the caller's id must not replace
    // the only handle on the value this purchase created.
    @Test
    void aDischargedIssuedRowKeepsItsOwnCouponNotTheCallers() {
        owed.setStatus(StripePurchase.Status.ISSUED);
        owed.setVoucherId("voucher-1");
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null, OWN,
                java.util.List.of("voucher-1", "voucher-2")));

        assertEquals(PurchaseDischargeService.Result.DISCHARGED, service.discharge(EVENT, "voucher-2", STALL));

        assertEquals("voucher-1", owed.getVoucherId());
    }

    // imani-wallet#289 (E4, U4 review): the re-check sweep (no issuer on the line) also asks about
    // rows whose delivery FAILED. It must not tell the operator the coupon was delivered.
    @Test
    void theRecheckDoesNotClaimADeliveryItDidNotSee() {
        owed.setStatus(StripePurchase.Status.ISSUED);
        owed.setVoucherId("voucher-1");
        gatewaySays(Fulfilment.NOT_FULFILLED, null);

        service.discharge(EVENT, "voucher-1", null);

        org.junit.jupiter.api.Assertions.assertFalse(owed.getLastFailure().contains("delivered"),
                owed.getLastFailure());
        org.junit.jupiter.api.Assertions.assertTrue(owed.getLastFailure().contains("voucher-1"),
                owed.getLastFailure());
    }

    // imani-wallet#287 (U4 review): a core older than U1 names no coupons, but its other facts
    // can still be wrong. A payment to someone else must stay REFUSED, not turn UNVERIFIABLE
    // just because the coupon list is missing.
    @Test
    void anOlderCoresWrongRecipientIsStillRefused() {
        owed.setStallPubkey(STALL);
        gatewaySays(new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, SELF, 2500L, "GBP", null, OWN));

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(1, owed.getAttempts());
    }

    // #288 option 1: a row registered under the key this service used BEFORE a rotation. Core
    // names the old key as creator; with that public key configured, inside its window, the row
    // is this service's own and discharges on the same facts as any other.
    @Test
    void aRowRegisteredUnderThePreviousKeyDischargesAfterRotation() {
        owed.setStatus(StripePurchase.Status.ISSUED);
        owed.setVoucherId("voucher-1");
        owed.setStallPubkey(STALL);
        service.withPreviousKeys(rollover(PREVIOUS, java.time.Instant.now().plus(java.time.Duration.ofDays(7))));
        gatewaySays(registeredBy(PREVIOUS));

        assertEquals(PurchaseDischargeService.Result.DISCHARGED, service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.DISCHARGED, owed.getStatus());
    }

    // #288: without the previous key configured, the same answer is somebody else's terms. That
    // is the strand the issue describes, and the guard the allowance must not weaken.
    @Test
    void aRowRegisteredUnderThePreviousKeyIsRefusedWhenItIsNotConfigured() {
        owed.setStatus(StripePurchase.Status.ISSUED);
        owed.setVoucherId("voucher-1");
        owed.setStallPubkey(STALL);
        gatewaySays(registeredBy(PREVIOUS));

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.ISSUED, owed.getStatus());
        assertEquals(1, owed.getAttempts());
        org.junit.jupiter.api.Assertions.assertTrue(owed.getLastFailure().contains("not this service"),
                owed.getLastFailure());
    }

    // #288: once the grace window has ended the previous key is a stranger again, so a leaked old
    // key cannot keep its authority after the rollover.
    @Test
    void aPreviousKeyWhoseWindowEndedIsRefused() {
        owed.setStallPubkey(STALL);
        service.withPreviousKeys(rollover(PREVIOUS, java.time.Instant.now().minusSeconds(60)));
        gatewaySays(registeredBy(PREVIOUS));

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
    }

    // #288: a previous key widens WHO may have registered the terms, never WHAT they are. A
    // payment to somebody else is refused under the old key exactly as under the current one.
    @Test
    void aPreviousKeyDoesNotExcuseAWrongRecipient() {
        owed.setStallPubkey(STALL);
        service.withPreviousKeys(rollover(PREVIOUS, java.time.Instant.now().plus(java.time.Duration.ofDays(7))));
        gatewaySays(new GatewayFulfilmentClient.Answer(
                Fulfilment.FULFILLED, STALL, SELF, 2500L, "GBP", null, PREVIOUS, java.util.List.of("voucher-1")));

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, "voucher-1", STALL));
    }

    // #288: configuring a previous key does not make every key this service's. A squatter is
    // still refused while the rollover is in force.
    @Test
    void aSquatterIsStillRefusedDuringARollover() {
        owed.setStallPubkey(STALL);
        service.withPreviousKeys(rollover(PREVIOUS, java.time.Instant.now().plus(java.time.Duration.ofDays(7))));
        gatewaySays(registeredBy(SQUATTER));

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, "voucher-1", STALL));
    }

    // gc#255 F1: core answering with a 02/03-prefixed variant of a key is the same key, for the
    // current key and a previous one alike. A different key's prefixed form still does not match.
    @Test
    void creatorKeysAreComparedXOnlyAcrossParityVariants() {
        owed.setStallPubkey(STALL);
        service.withPreviousKeys(rollover(PREVIOUS, java.time.Instant.now().plus(java.time.Duration.ofDays(7))));
        GatewayFulfilmentClient.Answer ownCompressed = registeredBy("02" + OWN);
        GatewayFulfilmentClient.Answer previousCompressed = registeredBy("03" + PREVIOUS.toUpperCase());
        GatewayFulfilmentClient.Answer squatterCompressed = registeredBy("02" + SQUATTER);

        assertNull(PurchaseDischargeService.mismatch(owed, ownCompressed, OWN, PreviousGatewayKeys.none(), "voucher-1"));
        assertNull(PurchaseDischargeService.mismatch(owed, ownCompressed, "03" + OWN, PreviousGatewayKeys.none(),
                "voucher-1"));
        assertNull(PurchaseDischargeService.mismatch(owed, previousCompressed, OWN,
                rollover(PREVIOUS, java.time.Instant.now().plus(java.time.Duration.ofDays(1))), "voucher-1"));
        org.junit.jupiter.api.Assertions.assertNotNull(PurchaseDischargeService.mismatch(owed, squatterCompressed,
                OWN, PreviousGatewayKeys.none(), "voucher-1"));
        org.junit.jupiter.api.Assertions.assertNotNull(PurchaseDischargeService.mismatch(owed, registeredBy("04" + OWN),
                OWN, PreviousGatewayKeys.none(), "voucher-1"), "04 is not a parity prefix");
    }

    /** A real x-only key on secp256k1: the previous key this service registered under. */
    private static final String PREVIOUS = PreviousGatewayKeysTest.pubkeyOf("7".repeat(64));

    // Review of #303: a leaked OLD key must not squat rows recorded AFTER the rollover was adopted.
    // Those were registered by the current key, so core naming the old key as creator means
    // somebody holding it got there first. Refused, exactly like any other squatter.
    @Test
    void aRowRecordedAfterTheRolloverIsNotVouchedForByThePreviousKey() {
        owed.setStallPubkey(STALL);
        PreviousGatewayKeys keys = rollover(PREVIOUS, java.time.Instant.now().plus(java.time.Duration.ofDays(7)));
        service.withPreviousKeys(keys);
        owed.setCreatedAt(keys.adoptedAt().plusMillis(1));
        gatewaySays(registeredBy(PREVIOUS));

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, "voucher-1", STALL));
        assertEquals(StripePurchase.Status.OWED, owed.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(owed.getLastFailure().contains("not this service"),
                owed.getLastFailure());
    }

    // The same answer for a row that existed at adoption is ours: the cutoff is inclusive.
    @Test
    void aRowRecordedAtTheMomentOfAdoptionIsStillVouchedFor() {
        owed.setStallPubkey(STALL);
        PreviousGatewayKeys keys = rollover(PREVIOUS, java.time.Instant.now().plus(java.time.Duration.ofDays(7)));
        owed.setCreatedAt(keys.adoptedAt());

        assertNull(PurchaseDischargeService.mismatch(owed, registeredBy(PREVIOUS), OWN, keys, "voucher-1"));
        owed.setCreatedAt(null);
        org.junit.jupiter.api.Assertions.assertNotNull(
                PurchaseDischargeService.mismatch(owed, registeredBy(PREVIOUS), OWN, keys, "voucher-1"),
                "a row with no recording time is never vouched for by an old key");
        assertNull(PurchaseDischargeService.mismatch(owed, registeredBy(OWN), OWN, keys, "voucher-1"),
                "the current key does not depend on the recording time");
    }

    private static PreviousGatewayKeys rollover(String key, java.time.Instant until) {
        return PreviousGatewayKeys.parse(key, until.toString(), null, java.time.Clock.systemUTC());
    }

    private GatewayFulfilmentClient.Answer registeredBy(String creator) {
        return new GatewayFulfilmentClient.Answer(
                Fulfilment.FULFILLED, STALL, BUYER, 2500L, "GBP", null, creator, java.util.List.of("voucher-1"));
    }
}
