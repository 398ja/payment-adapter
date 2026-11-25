package xyz.tcheeric.payment.adapter.paypal.webhook.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.PaypalCheckout;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.Outcome;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.ParkedRows;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.ReconcileBudget;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StaleRows;

/**
 * Finds the PayPal purchases no webhook recorded: {@code StripePurchaseReconciler}'s job, for PayPal
 * (ADR 0002).
 *
 * <p>Stripe can list a connected account's completed sessions; PayPal cannot list a seller's orders.
 * So the worklist is this service's own {@code paypal_checkout}: every order it created and has not
 * seen settle. Each run, for every such order older than the grace period:
 *
 * <ul>
 *   <li>read it; approved and uncaptured, capture it, with the same {@code PayPal-Request-Id} the
 *       webhook and the buyer's return use, and record it ({@link PaypalCaptureService});</li>
 *   <li>captured already (a lost {@code PAYMENT.CAPTURE.COMPLETED}), record it, applying any refund
 *       or dispute that arrived while no row existed to land on;</li>
 *   <li>never approved and older than {@code abandon-after}, close it: nothing was paid.</li>
 * </ul>
 *
 * <p>Then the shared stale and parked sweeps, scoped to {@code processor='paypal'} and reading
 * PayPal, park and recheck PayPal rows exactly as they do Stripe's.
 *
 * <p>Safe on several replicas without a lease: the capture is idempotent at PayPal, the debt at the
 * listener (one event id per capture), and the checkout row is versioned.
 */
@Slf4j
public class PaypalPurchaseReconciler {

    private final PaypalCheckoutRepository checkouts;
    private final PaypalOrdersClient paypal;
    private final PaypalCaptureService captures;
    private final StaleRows staleRows;
    private final ParkedRows parkedRows;
    private final Settings settings;

    public PaypalPurchaseReconciler(PaypalCheckoutRepository checkouts, PaypalOrdersClient paypal,
            PaypalCaptureService captures, StaleRows staleRows, ParkedRows parkedRows, Settings settings) {
        this.checkouts = checkouts;
        this.paypal = paypal;
        this.captures = captures;
        this.staleRows = staleRows;
        this.parkedRows = parkedRows;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${purchase.reconcile.interval:PT10M}",
            initialDelayString = "${purchase.reconcile.initial-delay:PT2M}")
    public void tick() {
        if (!settings.enabled()) {
            return;
        }
        try {
            run(Instant.now());
        } catch (RuntimeException e) {
            log.warn("paypal_reconcile run_failed", e);
        }
    }

    /** One run. Visible for tests. */
    public void run(Instant now) {
        ReconcileBudget budget = new ReconcileBudget(settings.maxCallsPerRun());
        int recorded = sweepCheckouts(now, budget);
        if (recorded > 0) {
            log.warn("[alert] paypal_reconcile recorded {} paid purchase(s) no webhook delivered", recorded);
        }
        if (budget.isRateLimited()) {
            return;
        }
        int parked = staleRows.sweep(now, staleRows.newBudget());
        if (parked > 0) {
            log.warn("paypal_reconcile parked {} stale purchase(s) for a person", parked);
        }
        int found = parkedRows.sweep(parkedRows.newBudget());
        if (found > 0) {
            log.warn("[alert] paypal_reconcile found {} refund(s)/dispute(s) on parked purchases that no"
                    + " webhook delivered", found);
        }
    }

    private int sweepCheckouts(Instant now, ReconcileBudget budget) {
        List<PaypalCheckout> open = checkouts.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
                PaypalCheckout.Status.CREATED, now.minus(settings.lookback()), now.minus(settings.grace()),
                PageRequest.of(0, settings.batchSize()));
        int recorded = 0;
        for (PaypalCheckout checkout : open) {
            if (!budget.spend()) {
                log.warn("paypal_reconcile budget spent; remaining orders continue next run");
                break;
            }
            try {
                if (reconcile(checkout, now) == Outcome.RECORDED) {
                    recorded++;
                }
            } catch (PaypalApiException e) {
                if (e.isRateLimited()) {
                    budget.rateLimited();
                    log.warn("paypal_reconcile rate limited; stopping this run");
                    break;
                }
                log.warn("paypal_reconcile order {} left for the next run: {}", checkout.getOrderId(), e.getMessage());
            } catch (RuntimeException e) {
                // One order must not stop the rest: it stays CREATED and comes back next run.
                log.warn("paypal_reconcile order {} failed, left for the next run", checkout.getOrderId(), e);
            }
        }
        return recorded;
    }

    private Outcome reconcile(PaypalCheckout checkout, Instant now) {
        PaypalOrder order;
        try {
            order = paypal.getOrder(checkout.getPaypalMerchantId(), checkout.getOrderId());
        } catch (PaypalApiException e) {
            if (e.isNotFound()) {
                // PayPal forgets an order that was never approved once it expires.
                captures.abandon(checkout, "order not found on PayPal");
                return Outcome.DECLINED;
            }
            throw e;
        }
        Outcome outcome = captures.settle(checkout, order, true);
        if (outcome == Outcome.NOT_APPROVED && checkout.getCreatedAt().isBefore(now.minus(settings.abandonAfter()))) {
            captures.abandon(checkout, "never approved; abandoned after " + settings.abandonAfter());
            return Outcome.DECLINED;
        }
        return outcome;
    }

    /**
     * @param lookback      how far back an order may still be found; older ones are left alone
     * @param grace         orders younger than this are left to their webhook and the buyer's return
     * @param abandonAfter  an order never approved this long is closed: PayPal expires it anyway
     */
    public record Settings(boolean enabled, Duration lookback, Duration grace, Duration abandonAfter,
                           int batchSize, int maxCallsPerRun) {
        public Settings {
            if (batchSize < 1 || maxCallsPerRun < 1) {
                throw new IllegalArgumentException("paypal reconcile batch size and call budget must be positive");
            }
        }
    }
}
