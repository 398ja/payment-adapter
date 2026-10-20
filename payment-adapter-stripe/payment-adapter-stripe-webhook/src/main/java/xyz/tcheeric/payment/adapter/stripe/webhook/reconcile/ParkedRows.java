package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.LivePayment;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.LivePaymentUnavailableException;

/**
 * The reconciler's live check of NEEDS_ATTENTION rows (#286 item 3): a refund or dispute whose webhook
 * was missed while a row waited for a person is found and recorded before the operator looks at it,
 * so the operator console and the attention gauge show Stripe's facts.
 *
 * <p>Records facts only, through {@link StripeLivePaymentCheck} (the webhook's listener path). Never
 * moves a parked row: that stays the operator's call, and their {@code retry} re-reads live anyway.
 *
 * <p>Bounded like {@link StaleRows}: at most {@code batchSize} reads a run, rotating through the parked
 * rows by {@code created_at} so a long list is covered over several runs rather than its head forever.
 * The position is per replica and in memory; a restart starts again from the oldest, which is harmless.
 */
@Slf4j
@RequiredArgsConstructor
class ParkedRows {

    private final StripeLivePaymentCheck livePayments;
    private final StripePurchaseRepository purchases;
    private final int batchSize;
    private Instant after = Instant.EPOCH;

    ReconcileBudget newBudget() {
        return new ReconcileBudget(batchSize);
    }

    /** @return parked rows on which the live read found a refund or dispute no webhook delivered */
    int sweep(ReconcileBudget budget) {
        List<StripePurchase> page = purchases.findWithPaymentIntentCreatedAfter(
                Status.NEEDS_ATTENTION, after, PageRequest.of(0, batchSize));
        after = page.size() < batchSize ? Instant.EPOCH : page.get(page.size() - 1).getCreatedAt();
        int found = 0;
        for (StripePurchase row : page) {
            if (!budget.spend()) {
                break;
            }
            try {
                LivePayment live = livePayments.refresh(row);
                found += live.newFacts() ? 1 : 0;
            } catch (LivePaymentUnavailableException e) {
                if (isRateLimited(e)) {
                    budget.rateLimited();
                    break;
                }
                log.info("stripe_reconcile parked row {} not read: {}", row.getEventId(), e.getMessage());
            }
        }
        return found;
    }

    private static boolean isRateLimited(LivePaymentUnavailableException e) {
        return e.getCause() instanceof StripeReconcileException cause && cause.isRateLimited();
    }
}
