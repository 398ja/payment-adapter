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
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.ReversedPurchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The owner's refund policy (#265), as far as U6 goes (#267): a refund before issue makes the
 * row REFUNDED and it is never minted; after issue only the facts are recorded (the treatment of an
 * issued coupon is undecided); a dispute is recorded and never changes the status.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordingPurchaseReversalTest {

    @Mock private StripePurchaseRepository purchases;
    @Mock private ConnectedStripeAccountRepository accounts;

    private RecordingPurchaseListener listener;
    private StripePurchase row;

    @BeforeEach
    void setUp() {
        listener = new RecordingPurchaseListener(purchases, accounts);
        row = new StripePurchase();
        row.setEventId("evt_paid");
        row.setPaymentIntentId("pi_1");
        row.setAmountMinor(500L);
        row.setStatus(StripePurchase.Status.OWED);
        when(purchases.findByPaymentIntentId("pi_1")).thenReturn(Optional.of(row));
        when(purchases.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static ReversedPurchase refund(long cumulative) {
        return new ReversedPurchase("evt_r" + cumulative, "pi_1", "ch_1", "acct_1", cumulative, 500L, false);
    }

    @Test
    void aRefundBeforeIssueMakesTheRowRefunded() {
        // S12: a refunded payment must never be minted. OWED -> REFUNDED, which no claim matches.
        listener.onPurchaseReversed(refund(500));

        assertEquals(StripePurchase.Status.REFUNDED, row.getStatus());
        assertEquals(500L, row.getRefundedAmountMinor());
    }

    @Test
    void aPartialRefundBeforeIssueAlsoRefundsTheRow() {
        // Before issue, any refund stops the mint: no coupon is better than one backed by
        // less than it says.
        listener.onPurchaseReversed(refund(100));

        assertEquals(StripePurchase.Status.REFUNDED, row.getStatus());
    }

    @Test
    void aRefundWhileAMintIsInFlightAlsoRefundsTheRow() {
        // ISSUING has no coupon yet. If the in-flight mint lands, discharge keeps its voucher id
        // for revocation instead of closing the row.
        row.setStatus(StripePurchase.Status.ISSUING);

        listener.onPurchaseReversed(refund(500));

        assertEquals(StripePurchase.Status.REFUNDED, row.getStatus());
        assertNull(row.getClaimedUntil());
    }

    @Test
    void aRefundAfterIssueRecordsTheAmountAndKeepsTheStatus() {
        // A coupon exists. U6 changes nothing about it: it records the cumulative refund so
        // backed = paid - refunded is derivable.
        row.setStatus(StripePurchase.Status.DISCHARGED);
        row.setVoucherId("v1");

        listener.onPurchaseReversed(refund(200));

        assertEquals(StripePurchase.Status.DISCHARGED, row.getStatus());
        assertEquals(200L, row.getRefundedAmountMinor());
    }

    @Test
    void theRefundedAmountIsCumulativeAndNeverGoesBackwards() {
        // Stripe reports amount_refunded as a running total; events can arrive out of order or
        // twice. The larger figure always wins, so a redelivery is a no-op.
        row.setStatus(StripePurchase.Status.ISSUED);
        row.setVoucherId("v1");

        listener.onPurchaseReversed(refund(300));
        listener.onPurchaseReversed(refund(100));
        listener.onPurchaseReversed(refund(300));

        assertEquals(300L, row.getRefundedAmountMinor());
    }

    @Test
    void aDisputeIsRecordedAndNeverChangesTheStatus() {
        // A dispute can be won (owner, #265): recorded, no status change.
        row.setStatus(StripePurchase.Status.DISCHARGED);

        listener.onPurchaseReversed(new ReversedPurchase("evt_d", "pi_1", "ch_1", "acct_1", null, 500L, true));

        assertTrue(row.isDisputed());
        assertEquals(StripePurchase.Status.DISCHARGED, row.getStatus());
        assertNull(row.getRefundedAmountMinor());
    }

    @Test
    void anUnknownPurchaseIsAcknowledgedNotRefused() {
        // Retrying can never find it; refusing would only make Stripe retry for three days.
        when(purchases.findByPaymentIntentId("pi_1")).thenReturn(Optional.empty());

        listener.onPurchaseReversed(refund(500));

        verify(purchases, never()).save(any());
    }
}
