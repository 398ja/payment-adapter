package xyz.tcheeric.payment.adapter.core.rest.reconcile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.reconcile.MintForwardRetrier;
import xyz.tcheeric.payment.adapter.core.rest.repository.QuoteRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Re-delivers settled payments the mint was never told about (398ja/cashu-mint#462).
 *
 * <p>A payment settles, the adapter marks the quote {@code PAID}, and forwards it to the mint.
 * That forward already retries three times inside {@code HttpMintWebhookForwarder} — but when
 * the retries are exhausted the payment is simply gone as far as the mint is concerned. The
 * customer's money has been taken and the mint cannot issue against a funding event it never
 * received. Seven such payments accumulated on staging, the oldest three weeks old, and 218
 * settled quotes stood against 130 webhook events the mint had ever seen.
 *
 * <p>The webhook handler now records a successful forward, so {@code mintNotifiedAt IS NULL} on
 * a {@code PAID} quote means precisely "money taken, mint not told". This sweep is what acts on
 * that: the inline forward is one code path that must work, and this catches everything it
 * misses — a mint restart, a network partition, a deploy landing mid-payment.
 *
 * <p><strong>Re-delivery is safe because the mint's webhook is idempotent</strong> on
 * {@code (provider, provider_event_id)}. A payment that did arrive is classified
 * {@code duplicate} and changes nothing, so the sweep cannot double-fund a voucher. That
 * property is what makes it acceptable to retry rather than requiring an operator.
 *
 * <p><strong>Direction.</strong> Like cashu-mint's own voucher-funding sweep and unlike its melt
 * reconciler, this sweeps <em>toward</em> completing the obligation: the money has already been
 * taken, so delivering the notification is the conservative action and abandoning it is the one
 * that loses value. There is no expiring a payment that has settled.
 *
 * <p>Disabled by default. The seven known stranded payments are weeks old and re-delivering them
 * would mint value against payments taken long ago, including one whose mint quote still reads
 * {@code UNPAID} — a decision for an operator rather than a side effect of deploying. Enable it
 * once that has been decided; until then the gauge alone makes the backlog visible.
 */
@Slf4j
@Component
public class PaidQuoteForwardReconciler {

    private static final int DEFAULT_BATCH_SIZE = 100;

    private final QuoteRepository quotes;
    private final MintForwardRetrier retrier;
    private final Duration gracePeriod;
    private final int batchSize;
    private final boolean enabled;

    public PaidQuoteForwardReconciler(
            @Autowired(required = false) QuoteRepository quotes,
            @Autowired(required = false) MintForwardRetrier retrier,
            @Value("${mint.webhook.reconcile.enabled:false}") boolean enabled,
            @Value("${mint.webhook.reconcile.grace-period:PT5M}") Duration gracePeriod,
            @Value("${mint.webhook.reconcile.batch-size:100}") int batchSize) {
        this.quotes = quotes;
        this.retrier = retrier;
        this.enabled = enabled;
        this.gracePeriod = gracePeriod;
        // A non-positive batch size would make the sweep a silent no-op, which is the one
        // failure a safety net must not have: it looks exactly like a healthy system with
        // nothing to do.
        if (batchSize <= 0) {
            log.warn("paid_quote_forward_reconcile invalid_batch_size={} — using {}",
                    batchSize, DEFAULT_BATCH_SIZE);
            this.batchSize = DEFAULT_BATCH_SIZE;
        } else {
            this.batchSize = batchSize;
        }
    }

    @Scheduled(fixedDelayString = "${mint.webhook.reconcile.interval:PT60S}")
    public void reconcileTick() {
        if (!enabled || quotes == null || retrier == null) {
            return;
        }
        try {
            sweepPaidButNotForwarded();
        } catch (RuntimeException e) {
            // One bad tick must not stop the schedule: the next sweep is the recovery.
            log.warn("paid_quote_forward_reconcile sweep_failed", e);
        }
    }

    @Transactional
    void sweepPaidButNotForwarded() {
        List<GatewayQuote> stranded = quotes.findPaidButNotForwarded(
                Instant.now().minus(gracePeriod), Limit.of(batchSize));
        if (stranded.size() >= batchSize) {
            // Oldest first with a bounded batch, so a payment that can never be delivered holds
            // the front of every tick. The gauge is non-zero either way and cannot tell the two
            // apart; this line is what distinguishes a backlog draining from one that is stuck.
            log.warn("paid_quote_forward_reconcile batch_full size={} — backlog exceeds one tick",
                    batchSize);
        }
        for (GatewayQuote quote : stranded) {
            redeliver(quote);
        }
    }

    /**
     * Re-delivers one payment through the same forwarder the webhook uses, so the two cannot
     * diverge on what counts as delivered.
     *
     * <p>A quote that still will not deliver is left alone and logged. It stays {@code PAID} with
     * a null stamp, which is exactly where the gauge can see it — abandoning the record would
     * make the money invisible again, which is the defect this exists to fix.
     */
    private void redeliver(GatewayQuote quote) {
        try {
            if (retrier.retryForward(quote)) {
                quote.setMintNotifiedAt(Instant.now());
                quotes.save(quote);
                log.warn("paid_quote_forward_reconcile recovered quote_id={} amount={} "
                                + "reason=settled_but_mint_never_told",
                        quote.getQuoteId(), quote.getAmount());
                return;
            }
            log.error("[alert] paid_quote_forward_reconcile undeliverable quote_id={} amount={} "
                            + "— settled payment the mint still has not accepted",
                    quote.getQuoteId(), quote.getAmount());
        } catch (RuntimeException e) {
            log.error("[alert] paid_quote_forward_reconcile failed quote_id={}",
                    quote.getQuoteId(), e);
        }
    }
}
