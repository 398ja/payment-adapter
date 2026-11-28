package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.CompletedSession;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/**
 * The reconciler recovers paid Checkout Sessions whose webhook was lost. A plan's session is paid too, but it is not
 * a one-off purchase: its instalments are recorded from their invoices. Recording the session as well would issue the
 * first instalment's vouchers twice.
 */
class LostPurchaseRecorderPlanTest {

    // A paid subscription-mode session (it names a plan) is skipped: nothing is read from Stripe or recorded.
    @Test
    void aPlansSessionIsNeverRecordedAsAOnePurchase() {
        StripeReconcileSource stripe = mock(StripeReconcileSource.class);
        StripePurchaseListener listener = mock(StripePurchaseListener.class);
        StripePurchaseRepository purchases = mock(StripePurchaseRepository.class);
        LostPurchaseRecorder recorder = new LostPurchaseRecorder(
                stripe, purchases, listener, mock(TransactionOperations.class), new PaymentMetrics());
        CompletedSession plan = new CompletedSession("cs_plan", Instant.now(), "paid", "pi_1", 5000L, "gbp",
                Map.of("plan_id", "p1"), false);

        LostPurchaseRecorder.Outcome outcome = recorder.reconcile("acct_farm", plan, mock(ReconcileBudget.class));

        assertThat(outcome).isEqualTo(LostPurchaseRecorder.Outcome.FOREIGN);
        verifyNoInteractions(stripe);
        verifyNoInteractions(listener);
    }
}
