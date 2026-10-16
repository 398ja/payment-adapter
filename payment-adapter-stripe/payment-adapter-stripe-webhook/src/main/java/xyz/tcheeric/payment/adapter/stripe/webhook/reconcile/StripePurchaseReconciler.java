package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeReconcileCursor;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripeReconcileCursorRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.LostPurchaseRecorder.Outcome;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.CompletedSession;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.SessionPage;

/**
 * Reconciles {@code stripe_purchase} against Stripe (payment-adapter#272, card atomicity audit
 * U10, S10).
 *
 * <p>Webhooks were the only way a paid card purchase was recorded, so a DB restore or a webhook
 * outage beyond Stripe's retry window lost the debt with no alert. Each run:
 *
 * <ol>
 *   <li>For each {@code charges_enabled} connected account, leases its cursor (one replica per
 *       account; PA has no ShedLock, so the same conditional-UPDATE lease the purchase claim uses),
 *       lists {@code status=complete} Checkout Sessions created in
 *       {@code [created_through - overlap, now - grace]} on that account, and records each paid one
 *       with no row ({@link LostPurchaseRecorder}: live PaymentIntent read first).</li>
 *   <li>Parks stale ISSUING/ISSUED and BLOCKED rows for a person ({@link StaleRows}).</li>
 * </ol>
 *
 * <p><b>Bounded.</b> Every Stripe call spends from a per-run budget; a 429 ends the run. A pass that
 * does not finish keeps its window and position ({@code pass_until}, {@code resume_after}) and the
 * next run continues it, so a large backlog drains over runs instead of re-reading page one.
 */
@Slf4j
public class StripePurchaseReconciler {

    private final ConnectedStripeAccountRepository accounts;
    private final StripeReconcileCursorRepository cursors;
    private final StripeReconcileSource stripe;
    private final LostPurchaseRecorder recorder;
    private final StaleRows staleRows;
    private final TransactionOperations transactions;
    private final ReconcilerSettings settings;
    private final String worker;

    StripePurchaseReconciler(ConnectedStripeAccountRepository accounts, StripeReconcileCursorRepository cursors,
            StripeReconcileSource stripe, LostPurchaseRecorder recorder, StaleRows staleRows,
            TransactionOperations transactions, ReconcilerSettings settings, String worker) {
        this.accounts = accounts;
        this.cursors = cursors;
        this.stripe = stripe;
        this.recorder = recorder;
        this.staleRows = staleRows;
        this.transactions = transactions;
        this.settings = settings;
        this.worker = worker;
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
            // One bad run must not stop the schedule: the next run is the recovery.
            log.warn("stripe_reconcile run_failed", e);
        }
    }

    /** One run. Visible for tests. */
    void run(Instant now) {
        ReconcileBudget budget = new ReconcileBudget(settings.maxStripeCallsPerRun());
        for (ConnectedStripeAccount account : accounts.findByChargesEnabledTrue()) {
            if (budget.exhausted()) {
                log.warn("stripe_reconcile budget spent; remaining accounts continue next run");
                break;
            }
            reconcileAccount(account.getStripeAccountId(), now, budget);
        }
        int parked = staleRows.sweep(now, budget);
        if (parked > 0) {
            log.warn("stripe_reconcile parked {} stale purchase(s) for a person", parked);
        }
    }

    private void reconcileAccount(String accountId, Instant now, ReconcileBudget budget) {
        if (!lease(accountId, now)) {
            return;
        }
        StripeReconcileCursor cursor = cursors.findById(accountId).orElseThrow();
        try {
            walk(cursor, now, budget);
        } catch (StripeReconcileException e) {
            if (e.isRateLimited()) {
                budget.rateLimited();
            }
            log.warn("stripe_reconcile account {} stopped, cursor kept: {}", accountId, e.getMessage());
        } finally {
            cursor.setLeaseUntil(null);
            cursor.setLastRunAt(now);
            save(cursor, now);
        }
    }

    private void walk(StripeReconcileCursor cursor, Instant now, ReconcileBudget budget) {
        if (cursor.getPassUntil() == null) {
            cursor.setPassUntil(now.minus(settings.grace()));
            cursor.setResumeAfter(null);
        }
        Instant from = cursor.getCreatedThrough().minus(settings.overlap());
        while (budget.spend()) {
            SessionPage page = stripe.completeSessions(cursor.getAccountId(), from, cursor.getPassUntil(),
                    cursor.getResumeAfter(), settings.pageSize());
            if (!handle(cursor, page.sessions(), budget)) {
                return;
            }
            if (!page.hasMore()) {
                cursor.setCreatedThrough(cursor.getPassUntil());
                cursor.setPassUntil(null);
                cursor.setResumeAfter(null);
                return;
            }
            save(cursor, now);
        }
    }

    /** @return false when the budget ran out mid-page; the position stays at the last handled session */
    private boolean handle(StripeReconcileCursor cursor, List<CompletedSession> sessions, ReconcileBudget budget) {
        for (CompletedSession session : sessions) {
            if (recorder.reconcile(cursor.getAccountId(), session, budget) == Outcome.DEFERRED) {
                return false;
            }
            cursor.setResumeAfter(session.id());
        }
        return true;
    }

    private boolean lease(String accountId, Instant now) {
        if (!cursors.existsById(accountId)) {
            createCursor(accountId, now);
        }
        Integer leased = transactions.execute(status ->
                cursors.lease(accountId, worker, now, now.plus(settings.lease())));
        return leased != null && leased == 1;
    }

    private void createCursor(String accountId, Instant now) {
        StripeReconcileCursor cursor = new StripeReconcileCursor();
        cursor.setAccountId(accountId);
        cursor.setCreatedThrough(now.minus(settings.initialLookback()));
        cursor.setUpdatedAt(now);
        try {
            transactions.executeWithoutResult(status -> cursors.save(cursor));
        } catch (DataIntegrityViolationException raced) {
            log.debug("stripe_reconcile cursor for {} created by another replica", accountId);
        }
    }

    private void save(StripeReconcileCursor cursor, Instant now) {
        cursor.setUpdatedAt(now);
        transactions.executeWithoutResult(status -> cursors.save(cursor));
    }

    /**
     * @param maxStripeCallsPerRun every list page and PaymentIntent read counts
     * @param grace                sessions younger than this are left to their webhook
     * @param lease                how long one replica holds an account's cursor
     */
    public record ReconcilerSettings(
            boolean enabled,
            Duration overlap,
            Duration grace,
            Duration initialLookback,
            Duration lease,
            int pageSize,
            int maxStripeCallsPerRun) {

        public ReconcilerSettings {
            pageSize = Math.max(1, Math.min(100, pageSize));
            maxStripeCallsPerRun = Math.max(1, maxStripeCallsPerRun);
        }
    }
}
