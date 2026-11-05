package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.List;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An email or code gift (imani-wallet#410 G14) was delivered and never discharged: it has no DM,
 * so no completed send answers its request, and CardPaidNotIssued fired forever (staging
 * stripe_purchase 024720b9, voucher 7c2e2186). Its evidence is the LOCK, which core reads from
 * gateway-customer's own token: never the issuer's word.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GiftDischargeTest {

    private static final String EVENT = "evt_1UYScNLHE9AvsgYYMZy7CBPg";
    private static final String REQUEST = "23d7964798e2ea66680ead08a13be4db";
    private static final String STALL = "1c4be7be8c27506e0ce4b7df7c051c7a7f50e650bdf8d9305a63324febafead1";
    /** R: the buyer's refund key, the only key a locked gift pays before it is claimed. */
    private static final String R = "2c0588136557aab5afcceb9299a02b5157e4a84fa633cc5fb4740c82d0ecdabf";
    private static final String VOUCHER = "7c2e2186-bb11-4f0f-ac07-b29fab6086fc";
    private static final String OWN = "e".repeat(64);
    /** What purchase-issuer reports as issuerId: the coupon's issuer, the stall. */
    private static final String ISSUER = STALL;

    @Mock private StripePurchaseRepository purchases;
    @Mock private GatewayFulfilmentClient fulfilment;

    private PurchaseDischargeService service;
    private StripePurchase gift;

    @BeforeEach
    void anIssuedEmailGift() {
        service = new PurchaseDischargeService(purchases, fulfilment);
        gift = new StripePurchase();
        gift.setEventId(EVENT);
        gift.setPaymentRequestId(REQUEST);
        gift.setStatus(StripePurchase.Status.ISSUED);
        gift.setDeliveryKind("email");
        gift.setRecipientPubkey(null);
        gift.setRefundPubkey(R);
        gift.setStallPubkey(STALL);
        gift.setVoucherId(VOUCHER);
        gift.setAmountMinor(1000L);
        gift.setCurrency("gbp");
        gift.setCreatedAt(java.time.Instant.now().minusSeconds(3600));
        when(purchases.findByEventId(EVENT)).thenReturn(Optional.of(gift));
        when(purchases.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fulfilment.publicKeyHex()).thenReturn(OWN);
    }

    private static GatewayFulfilmentClient.Answer unpaid() {
        return new GatewayFulfilmentClient.Answer(Fulfilment.NOT_FULFILLED, STALL, null, 0L, "GBP", "unpaid", OWN, List.of());
    }

    private static GatewayFulfilmentClient.Answer paidTo(String recipient, String voucher) {
        return new GatewayFulfilmentClient.Answer(Fulfilment.FULFILLED, STALL, recipient, 1000L, "GBP", null, OWN,
                List.of(voucher));
    }

    // RED before the fix: the staging row. Delivered by wrap and email, so the verdict is "unpaid"
    // and the discharge was refused every 15 minutes. Now core is asked to read the coupon's lock,
    // and once it has, the verdict counts it and the gift closes.
    @Test
    void aDeliveredEmailGiftIsDischargedOnceCoreHasReadItsLock() {
        when(fulfilment.check(REQUEST)).thenReturn(unpaid(), paidTo(R, VOUCHER));
        when(fulfilment.recordLockedGift(REQUEST, VOUCHER)).thenReturn(GatewayFulfilmentClient.LockedGift.RECORDED);

        assertEquals(PurchaseDischargeService.Result.DISCHARGED, service.discharge(EVENT, VOUCHER, ISSUER));
        assertEquals(StripePurchase.Status.DISCHARGED, gift.getStatus());
        assertEquals(VOUCHER, gift.getVoucherId());
    }

    // Code gifts are recorded with delivery_kind email too (gift_kind code): the same path.
    @Test
    void aDeliveredCodeGiftIsDischargedTheSameWay() {
        gift.setGiftKind("code");
        gift.setShopKey("5a0b".repeat(16));
        when(fulfilment.check(REQUEST)).thenReturn(unpaid(), paidTo(R, VOUCHER));
        when(fulfilment.recordLockedGift(REQUEST, VOUCHER)).thenReturn(GatewayFulfilmentClient.LockedGift.RECORDED);

        assertEquals(PurchaseDischargeService.Result.DISCHARGED, service.discharge(EVENT, VOUCHER, ISSUER));
    }

    // The stuck row closes by itself: the sweep has no issuer on the line, and still discharges it.
    @Test
    void theRecheckSweepDischargesAStuckEmailGift() {
        when(purchases.findIssuedDueForRecheck(eq(StripePurchase.Status.ISSUED), any(), any(), any()))
                .thenReturn(List.of(gift));
        when(fulfilment.check(REQUEST)).thenReturn(unpaid(), paidTo(R, VOUCHER));
        when(fulfilment.recordLockedGift(REQUEST, VOUCHER)).thenReturn(GatewayFulfilmentClient.LockedGift.RECORDED);

        assertEquals(1, service.recheckIssued(java.time.Instant.now()));
        assertEquals(StripePurchase.Status.DISCHARGED, gift.getStatus());
    }

    // Core read the coupon and it is not this gift locked as asked (unlocked, refunds elsewhere,
    // another stall's): refused, and recorded against the row for a person.
    @Test
    void aGiftCoreRefusesIsRefusedAndRecorded() {
        when(fulfilment.check(REQUEST)).thenReturn(unpaid());
        when(fulfilment.recordLockedGift(REQUEST, VOUCHER)).thenReturn(GatewayFulfilmentClient.LockedGift.REFUSED);

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, VOUCHER, ISSUER));
        assertEquals(StripePurchase.Status.ISSUED, gift.getStatus());
        assertEquals(1, gift.getAttempts());
    }

    // An outage, or a core without the endpoint yet: nothing learned, nobody accused.
    @Test
    void aCoreThatCannotRecordLeavesTheGiftUnverifiableNotRefused() {
        when(fulfilment.check(REQUEST)).thenReturn(unpaid());
        when(fulfilment.recordLockedGift(REQUEST, VOUCHER)).thenReturn(GatewayFulfilmentClient.LockedGift.UNAVAILABLE);

        assertEquals(PurchaseDischargeService.Result.UNVERIFIABLE, service.discharge(EVENT, VOUCHER, ISSUER));
        assertEquals(StripePurchase.Status.ISSUED, gift.getStatus());
        assertEquals(0, gift.getAttempts());
    }

    // The ROW's coupon is the one asked about (imani-wallet#287), not whatever the caller names.
    @Test
    void theRowsOwnCouponIsTheOneAskedAbout() {
        when(fulfilment.check(REQUEST)).thenReturn(unpaid(), paidTo(R, VOUCHER));
        when(fulfilment.recordLockedGift(REQUEST, VOUCHER)).thenReturn(GatewayFulfilmentClient.LockedGift.RECORDED);

        assertEquals(PurchaseDischargeService.Result.DISCHARGED, service.discharge(EVENT, "some-other-coupon", ISSUER));
        verify(fulfilment, never()).recordLockedGift(REQUEST, "some-other-coupon");
        assertEquals(VOUCHER, gift.getVoucherId());
    }

    // Recording is not the verdict: a verdict that counts value to anyone but R does not close it.
    @Test
    void aVerdictPayingAnotherKeyStillDoesNotDischarge() {
        when(fulfilment.check(REQUEST)).thenReturn(unpaid(), paidTo("a".repeat(64), VOUCHER));
        when(fulfilment.recordLockedGift(REQUEST, VOUCHER)).thenReturn(GatewayFulfilmentClient.LockedGift.RECORDED);

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, VOUCHER, ISSUER));
        assertEquals(StripePurchase.Status.ISSUED, gift.getStatus());
    }

    // A gift row recorded before registration (or whose PUT failed) is registered late to pay R.
    @Test
    void anUnregisteredGiftIsRegisteredLateToPayR() {
        when(fulfilment.check(REQUEST)).thenReturn(
                new GatewayFulfilmentClient.Answer(Fulfilment.NOT_FULFILLED, null, null, 0L, null, "unregistered"),
                unpaid(), paidTo(R, VOUCHER));
        when(fulfilment.register(REQUEST, R, STALL, 1000L, "GBP")).thenReturn(GatewayFulfilmentClient.Registration.REGISTERED);
        when(fulfilment.recordLockedGift(REQUEST, VOUCHER)).thenReturn(GatewayFulfilmentClient.LockedGift.RECORDED);

        assertEquals(PurchaseDischargeService.Result.DISCHARGED, service.discharge(EVENT, VOUCHER, ISSUER));
        verify(fulfilment).register(REQUEST, R, STALL, 1000L, "GBP");
    }

    // ─── the wallet-purchase path is untouched ───

    // A wallet purchase has a buyer key and no delivery kind: core is never asked about a lock, and
    // an "unpaid" verdict is refused exactly as before.
    @Test
    void aWalletPurchaseNeverTakesTheGiftPath() {
        gift.setDeliveryKind(null);
        gift.setRecipientPubkey("a".repeat(64));
        when(fulfilment.check(REQUEST)).thenReturn(unpaid());

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, VOUCHER, ISSUER));
        verify(fulfilment, never()).recordLockedGift(anyString(), anyString());
    }

    // A handle gift has a recipient key and a DM: it is verified like a wallet purchase.
    @Test
    void aHandleGiftNeverTakesTheLockedGiftPath() {
        gift.setDeliveryKind("handle");
        gift.setRecipientPubkey("a".repeat(64));
        when(fulfilment.check(REQUEST)).thenReturn(unpaid());

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, VOUCHER, ISSUER));
        verify(fulfilment, never()).recordLockedGift(anyString(), anyString());
    }

    // Review of #316: the locked path is for a row with NO recipient key. An email-kind row that has
    // one (a wallet-signed-in delivery, or a future kind) is verified like a wallet purchase, so a
    // value paid to R can never stand in for the recipient it names.
    @Test
    void anEmailRowThatNamesARecipientKeyNeverTakesTheLockedGiftPath() {
        gift.setRecipientPubkey("a".repeat(64));
        when(fulfilment.check(REQUEST)).thenReturn(unpaid());

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, VOUCHER, ISSUER));
        verify(fulfilment, never()).recordLockedGift(anyString(), anyString());
        assertEquals("a".repeat(64), PurchaseDischargeService.registeredRecipient(gift));
    }

    // A wallet purchase is still never satisfied by value to someone else, R or not.
    @Test
    void aWalletPurchaseIsNotDischargedByValueToAnotherKey() {
        gift.setDeliveryKind(null);
        gift.setRecipientPubkey("a".repeat(64));
        when(fulfilment.check(REQUEST)).thenReturn(paidTo(R, VOUCHER));

        assertEquals(PurchaseDischargeService.Result.REFUSED, service.discharge(EVENT, VOUCHER, ISSUER));
    }
}
