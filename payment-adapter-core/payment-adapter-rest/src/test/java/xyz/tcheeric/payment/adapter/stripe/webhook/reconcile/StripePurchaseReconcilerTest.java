package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.AttentionReason;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeReconcileCursor;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripeReconcileCursorRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.RecordingPurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripePurchaseReconciler.ReconcilerSettings;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.CompletedSession;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.SessionPage;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.PaidPurchase;

/**
 * payment-adapter#272 (card atomicity audit S10): the reconciler against the real schema, the real
 * {@link RecordingPurchaseListener} and a scripted Stripe. A paid session with no row is recorded
 * once; a refunded or disputed one is never made mintable; stale rows are parked, never re-minted.
 */
@DataJpaTest
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class StripePurchaseReconcilerTest {

    /** The reconciler's classes are package-private, so the test lives here and brings its own boot config. */
    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackages = "xyz.tcheeric.payment.adapter.core.model.repository")
    static class JpaOnly {
    }

    private static final String ACCOUNT = "acct_stall1";
    private static final String BUYER = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired private StripePurchaseRepository purchases;
    @Autowired private ConnectedStripeAccountRepository accounts;
    @Autowired private StripeReconcileCursorRepository cursors;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TestEntityManager entityManager;

    private final ScriptedStripe stripe = new ScriptedStripe();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private StripePurchaseListener listener;
    private int maxCalls = 50;
    private int parkedBatch = 20;

    @BeforeEach
    void setUp() {
        ConnectedStripeAccount account = new ConnectedStripeAccount();
        account.setMerchantPubkey("b".repeat(64));
        account.setStripeAccountId(ACCOUNT);
        account.setChargesEnabled(true);
        account.setCreatedAt(NOW);
        account.setUpdatedAt(NOW);
        accounts.save(account);
        listener = new RecordingPurchaseListener(purchases, accounts);
    }

    private StripePurchaseReconciler reconciler() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        PaymentMetrics metrics = new PaymentMetrics(registry).preRegisterReconciled(Source.CARD);
        return new StripePurchaseReconciler(accounts, cursors, stripe,
                new LostPurchaseRecorder(stripe, purchases, listener, tx, metrics),
                new StaleRows(stripe, purchases, listener, tx, Duration.ofHours(1), Duration.ofDays(14), 20),
                new ParkedRows(new StripeLivePaymentCheck(stripe, listener), purchases, parkedBatch),
                tx,
                new ReconcilerSettings(true, Duration.ofHours(1), Duration.ofMinutes(10), Duration.ofDays(30),
                        Duration.ofMinutes(15), 2, maxCalls),
                "test-worker");
    }

    private double reconciled() {
        return registry.get(PaymentMetrics.RECONCILED).tag("source", "card").counter().count();
    }

    /** S10: a paid session whose row was lost to a restore is recorded once, OWED, as reconcile:<cs>. */
    @Test
    void aLostPaidPurchaseIsRecordedOnceAndOnlyOnce() {
        stripe.page(paid("cs_lost", "pi_lost"));

        reconciler().run(NOW);
        reconciler().run(NOW.plusSeconds(600));

        StripePurchase row = purchases.findByCheckoutSessionId("cs_lost").orElseThrow();
        assertThat(row.getEventId()).isEqualTo("reconcile:cs_lost");
        assertThat(row.getStatus()).isEqualTo(Status.OWED);
        assertThat(row.getPaymentIntentId()).isEqualTo("pi_lost");
        assertThat(row.getRecipientPubkey()).isEqualTo(BUYER);
        assertThat(row.getStallPubkey()).isEqualTo("b".repeat(64));
        assertThat(purchases.count()).isEqualTo(1);
        assertThat(reconciled()).isEqualTo(1.0);
    }

    /** A session its webhook already recorded is left alone: no second row, no count. */
    @Test
    void aRecordedPurchaseIsNotRecordedAgain() throws Exception {
        listener.onPurchasePaid(new PaidPurchase("evt_webhook", "cs_seen", "pi_seen", ACCOUNT, 500L, "gbp",
                Map.of("buyer_pubkey", BUYER), false));
        stripe.page(paid("cs_seen", "pi_seen"));

        reconciler().run(NOW);

        assertThat(purchases.count()).isEqualTo(1);
        assertThat(purchases.findByCheckoutSessionId("cs_seen").orElseThrow().getEventId()).isEqualTo("evt_webhook");
        assertThat(reconciled()).isZero();
        assertThat(stripe.paymentReads).isZero();
    }

    /** The webhook arriving after the reconciler records nothing: one payment, one row. */
    @Test
    void aLateWebhookDedupesAgainstTheReconciledRow() throws Exception {
        stripe.page(paid("cs_race", "pi_race"));
        reconciler().run(NOW);

        listener.onPurchasePaid(new PaidPurchase("evt_late", "cs_race", "pi_race", ACCOUNT, 500L, "gbp",
                Map.of("buyer_pubkey", BUYER), false));

        assertThat(purchases.count()).isEqualTo(1);
        assertThat(purchases.findByEventId("evt_late")).isEmpty();
    }

    /** Unpaid (async in flight) sessions are ignored and cost no PaymentIntent read. */
    @Test
    void anUnpaidSessionIsIgnored() {
        stripe.page(session("cs_unpaid", "pi_unpaid", "unpaid"));

        reconciler().run(NOW);

        assertThat(purchases.count()).isZero();
        assertThat(stripe.paymentReads).isZero();
    }

    /** Owner's rule: refunded before any row existed -> recorded REFUNDED in one step, never OWED. */
    @Test
    void aRefundedLostPurchaseIsRecordedRefundedNeverMintable() {
        stripe.page(paid("cs_ref", "pi_ref"));
        stripe.live.put("pi_ref", new PaymentState("ch_ref", 500L, 500L, false));

        reconciler().run(NOW);

        StripePurchase row = purchases.findByCheckoutSessionId("cs_ref").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Status.REFUNDED);
        assertThat(row.getRefundedAmountMinor()).isEqualTo(500L);
        assertThat(purchases.findClaimable(Status.OWED, Status.ISSUING, NOW)).isEmpty();
    }

    /** A partial refund is still a refund: never minted (#265: partial refunds are not supported). */
    @Test
    void aPartiallyRefundedLostPurchaseIsNotMintable() {
        stripe.page(paid("cs_part", "pi_part"));
        stripe.live.put("pi_part", new PaymentState("ch_part", 500L, 100L, false));

        reconciler().run(NOW);

        assertThat(purchases.findByCheckoutSessionId("cs_part").orElseThrow().getStatus()).isEqualTo(Status.REFUNDED);
    }

    /** Disputed before any row: recorded, disputed, parked for a person. Never OWED. */
    @Test
    void aDisputedLostPurchaseIsParked() {
        stripe.page(paid("cs_dis", "pi_dis"));
        stripe.live.put("pi_dis", new PaymentState("ch_dis", 500L, 0L, true));

        reconciler().run(NOW);

        StripePurchase row = purchases.findByCheckoutSessionId("cs_dis").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
        assertThat(row.getAttentionReason()).isEqualTo(AttentionReason.DISPUTED_BEFORE_ISSUE);
        assertThat(row.isDisputed()).isTrue();
    }

    /** The cursor moves to the end of the pass, and the next pass reads from there less the overlap. */
    @Test
    void theCursorAdvancesAndTheNextPassOverlaps() {
        stripe.page(paid("cs_1", "pi_1"));
        reconciler().run(NOW);

        StripeReconcileCursor cursor = cursors.findById(ACCOUNT).orElseThrow();
        assertThat(cursor.getCreatedThrough()).isEqualTo(NOW.minus(Duration.ofMinutes(10)));
        assertThat(cursor.getPassUntil()).isNull();
        assertThat(cursor.getLeaseUntil()).isNull();
        assertThat(stripe.windows.get(0).from()).isEqualTo(NOW.minus(Duration.ofDays(30)).minus(Duration.ofHours(1)));

        stripe.windows.clear();
        stripe.page();
        reconciler().run(NOW.plus(Duration.ofHours(2)));
        assertThat(stripe.windows.get(0).from()).isEqualTo(NOW.minus(Duration.ofMinutes(70)));
        assertThat(stripe.windows.get(0).until()).isEqualTo(NOW.plus(Duration.ofHours(2)).minus(Duration.ofMinutes(10)));
    }

    /** Bounded: a run out of budget keeps its place, and the next run continues the SAME pass. */
    @Test
    void aBoundedRunResumesWhereItStopped() {
        maxCalls = 2; // one page + one PaymentIntent read
        stripe.page(paid("cs_a", "pi_a"), paid("cs_b", "pi_b"));
        stripe.hasMore = true;

        reconciler().run(NOW);

        assertThat(purchases.findByCheckoutSessionId("cs_a")).isPresent();
        assertThat(purchases.findByCheckoutSessionId("cs_b")).isEmpty();
        StripeReconcileCursor cursor = cursors.findById(ACCOUNT).orElseThrow();
        assertThat(cursor.getResumeAfter()).isEqualTo("cs_a");
        assertThat(cursor.getPassUntil()).isEqualTo(NOW.minus(Duration.ofMinutes(10)));

        stripe.windows.clear();
        stripe.page(paid("cs_b", "pi_b"));
        stripe.hasMore = false;
        reconciler().run(NOW.plusSeconds(600));

        assertThat(stripe.windows.get(0).startingAfter()).isEqualTo("cs_a");
        assertThat(stripe.windows.get(0).until()).isEqualTo(NOW.minus(Duration.ofMinutes(10)));
        assertThat(purchases.findByCheckoutSessionId("cs_b")).isPresent();
        assertThat(cursors.findById(ACCOUNT).orElseThrow().getPassUntil()).isNull();
    }

    /** A Stripe failure stops the account without moving the cursor: nothing is skipped. */
    @Test
    void aStripeFailureKeepsTheCursor() {
        stripe.failListing = true;

        reconciler().run(NOW);

        StripeReconcileCursor cursor = cursors.findById(ACCOUNT).orElseThrow();
        assertThat(cursor.getCreatedThrough()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(cursor.getLeaseUntil()).isNull();
    }

    /** Another replica holding the account's lease means this run skips it. */
    @Test
    void aLeasedAccountIsSkipped() {
        StripeReconcileCursor cursor = new StripeReconcileCursor();
        cursor.setAccountId(ACCOUNT);
        cursor.setCreatedThrough(NOW.minus(Duration.ofDays(1)));
        cursor.setLeaseUntil(NOW.plusSeconds(300));
        cursor.setLeasedBy("other");
        cursor.setUpdatedAt(NOW);
        cursors.save(cursor);
        stripe.page(paid("cs_x", "pi_x"));

        reconciler().run(NOW);

        assertThat(stripe.windows).isEmpty();
        assertThat(purchases.count()).isZero();
    }

    /** An account without charges_enabled is never read. */
    @Test
    void accountsWithoutChargesAreNotRead() {
        ConnectedStripeAccount account = accounts.findByStripeAccountId(ACCOUNT).orElseThrow();
        account.setChargesEnabled(false);
        accounts.save(account);
        stripe.page(paid("cs_x", "pi_x"));

        reconciler().run(NOW);

        assertThat(stripe.windows).isEmpty();
    }

    /** No blind re-mint: ISSUING whose lease lapsed over an hour ago is parked stale_issuing. */
    @Test
    void aStaleIssuingRowIsParkedNotRetried() {
        StripePurchase row = persist("evt_issuing", "pi_issuing", Status.ISSUING, NOW.minus(Duration.ofHours(3)));
        row.setClaimedUntil(NOW.minus(Duration.ofMinutes(61)));
        purchases.save(row);
        stripe.page();

        reconciler().run(NOW);

        StripePurchase parked = purchases.findByEventId("evt_issuing").orElseThrow();
        assertThat(parked.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
        assertThat(parked.getAttentionReason()).isEqualTo(AttentionReason.STALE_ISSUING);
        assertThat(parked.getAttentionDetail()).contains("refunded 0 of 500");
    }

    /** A lease that lapsed minutes ago is the issuer's to retake, not the reconciler's. */
    @Test
    void aRecentlyLapsedLeaseIsLeftForTheIssuer() {
        StripePurchase row = persist("evt_recent", "pi_recent", Status.ISSUING, NOW.minus(Duration.ofHours(3)));
        row.setClaimedUntil(NOW.minus(Duration.ofMinutes(5)));
        purchases.save(row);
        stripe.page();

        reconciler().run(NOW);

        assertThat(purchases.findByEventId("evt_recent").orElseThrow().getStatus()).isEqualTo(Status.ISSUING);
    }

    /** ISSUED past the re-check horizon is parked stale_issued with its voucher kept. */
    @Test
    void anIssuedRowPastTheHorizonIsParked() {
        StripePurchase row = persist("evt_issued", "pi_issued", Status.ISSUED, NOW.minus(Duration.ofDays(15)));
        row.setVoucherId("v1");
        purchases.save(row);
        stripe.page();

        reconciler().run(NOW);

        StripePurchase parked = purchases.findByEventId("evt_issued").orElseThrow();
        assertThat(parked.getAttentionReason()).isEqualTo(AttentionReason.STALE_ISSUED);
        assertThat(parked.getVoucherId()).isEqualTo("v1");
    }

    /**
     * Staging purchase #1 (PAID/BLOCKED by a NIP-98 401, not refunded): parked issue_blocked so it is
     * on the operator's list. It is NOT made OWED: an operator retries it once the cause is fixed.
     */
    @Test
    void anUnrefundedBlockedRowIsParkedForTheOperator() {
        StripePurchase row = persist("evt_blocked", "pi_blocked", Status.BLOCKED, NOW.minus(Duration.ofDays(1)));
        row.setLastFailure("gateway 401 NIP-98 url mismatch");
        purchases.save(row);
        stripe.page();

        reconciler().run(NOW);

        StripePurchase parked = purchases.findByEventId("evt_blocked").orElseThrow();
        assertThat(parked.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
        assertThat(parked.getAttentionReason()).isEqualTo(AttentionReason.ISSUE_BLOCKED);
        assertThat(parked.getAttentionDetail()).contains("401").contains("refunded 0 of 500");
    }

    /** A BLOCKED row Stripe says was refunded becomes REFUNDED, never retryable into a mint. */
    @Test
    void aRefundedBlockedRowBecomesRefunded() {
        persist("evt_blocked_ref", "pi_blocked_ref", Status.BLOCKED, NOW.minus(Duration.ofDays(1)));
        stripe.live.put("pi_blocked_ref", new PaymentState("ch_b", 500L, 500L, false));
        stripe.page();

        reconciler().run(NOW);

        StripePurchase row = purchases.findByEventId("evt_blocked_ref").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Status.REFUNDED);
        assertThat(row.getRefundedAmountMinor()).isEqualTo(500L);
    }

    /** A stale ISSUED row refunded after issue keeps its status facts (#265) and is still parked. */
    @Test
    void aRefundAfterIssueIsRecordedAsFactsOnly() {
        StripePurchase row = persist("evt_iss_ref", "pi_iss_ref", Status.ISSUED, NOW.minus(Duration.ofDays(20)));
        row.setVoucherId("v2");
        purchases.save(row);
        stripe.live.put("pi_iss_ref", new PaymentState("ch_i", 500L, 500L, false));
        stripe.page();

        reconciler().run(NOW);

        StripePurchase parked = purchases.findByEventId("evt_iss_ref").orElseThrow();
        assertThat(parked.getRefundedAmountMinor()).isEqualTo(500L);
        assertThat(parked.getAttentionReason()).isEqualTo(AttentionReason.STALE_ISSUED);
    }

    // ------------------------------------------------------- review of #282 (adversarial)

    /**
     * A refund landing between the live read and the commit: its webhook found no row and was
     * acknowledged, so nothing else will ever see it. The read after the commit does.
     */
    @Test
    void aRefundLandingWhileTheRowIsRecordedIsCaughtByTheReRead() {
        stripe.page(paid("cs_race_ref", "pi_race_ref"));
        stripe.liveSequence.put("pi_race_ref", new ArrayList<>(List.of(
                new PaymentState("ch_r", 500L, 0L, false),
                new PaymentState("ch_r", 500L, 500L, false))));

        reconciler().run(NOW);

        StripePurchase row = purchases.findByCheckoutSessionId("cs_race_ref").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Status.REFUNDED);
        assertThat(purchases.findClaimable(Status.OWED, Status.ISSUING, NOW)).isEmpty();
    }

    /** Same race with a dispute: parked, never left OWED. */
    @Test
    void aDisputeLandingWhileTheRowIsRecordedParksIt() {
        stripe.page(paid("cs_race_dis", "pi_race_dis"));
        stripe.liveSequence.put("pi_race_dis", new ArrayList<>(List.of(
                new PaymentState("ch_d", 500L, 0L, false),
                new PaymentState("ch_d", 500L, 0L, true))));

        reconciler().run(NOW);

        StripePurchase row = purchases.findByCheckoutSessionId("cs_race_dis").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
        assertThat(row.getAttentionReason()).isEqualTo(AttentionReason.DISPUTED_BEFORE_ISSUE);
    }

    /**
     * A constraint failure that is NOT a racing webhook (no row exists afterwards) must not be read
     * as "already recorded": that would advance the cursor past a paid, unrecorded session for good.
     */
    @Test
    void aConstraintFailureWithNoRowKeepsTheCursor() {
        listener = purchase -> {
            throw new org.springframework.dao.DataIntegrityViolationException("value too long for currency");
        };
        stripe.page(paid("cs_poison", "pi_poison"));

        reconciler().run(NOW);

        assertThat(purchases.findByCheckoutSessionId("cs_poison")).isEmpty();
        StripeReconcileCursor cursor = cursors.findById(ACCOUNT).orElseThrow();
        assertThat(cursor.getCreatedThrough()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(cursor.getResumeAfter()).isNull();
        assertThat(reconciled()).isZero();
    }

    /**
     * Stripe restricts accounts (charges_enabled false) after they have taken payments. A lost
     * purchase on such an account must still be found: an account with a cursor stays in scope.
     */
    @Test
    void anAccountWhoseChargesWereDisabledIsStillReconciled() {
        StripeReconcileCursor cursor = new StripeReconcileCursor();
        cursor.setAccountId(ACCOUNT);
        cursor.setCreatedThrough(NOW.minus(Duration.ofDays(1)));
        cursor.setUpdatedAt(NOW);
        cursors.save(cursor);
        ConnectedStripeAccount account = accounts.findByStripeAccountId(ACCOUNT).orElseThrow();
        account.setChargesEnabled(false);
        accounts.save(account);
        stripe.page(paid("cs_restricted", "pi_restricted"));

        reconciler().run(NOW);

        assertThat(purchases.findByCheckoutSessionId("cs_restricted")).isPresent();
    }

    /** The least recently reconciled account goes first, so a small budget cannot starve the tail. */
    @Test
    void theLeastRecentlyReconciledAccountGoesFirst() {
        cursorRanAt(ACCOUNT, NOW.minus(Duration.ofMinutes(1)));
        ConnectedStripeAccount other = new ConnectedStripeAccount();
        other.setMerchantPubkey("c".repeat(64));
        other.setStripeAccountId("acct_stall2");
        other.setChargesEnabled(true);
        other.setCreatedAt(NOW);
        other.setUpdatedAt(NOW);
        accounts.save(other);
        cursorRanAt("acct_stall2", NOW.minus(Duration.ofHours(1)));
        maxCalls = 1;
        stripe.page();

        reconciler().run(NOW);

        assertThat(stripe.windows).extracting(Window::accountId).containsExactly("acct_stall2");
    }

    /** Session listing that spends the whole budget must not starve the stale-row sweep. */
    @Test
    void staleRowsAreParkedEvenWhenListingSpendsTheBudget() {
        persist("evt_blocked_starved", "pi_bs", Status.BLOCKED, NOW.minus(Duration.ofDays(1)));
        maxCalls = 1;
        stripe.page();

        reconciler().run(NOW);

        assertThat(purchases.findByEventId("evt_blocked_starved").orElseThrow().getStatus())
                .isEqualTo(Status.NEEDS_ATTENTION);
    }

    /**
     * An issuer that re-claims a lapsed ISSUING row while the sweep reads Stripe is minting under a
     * live lease: the sweep re-checks staleness on the fresh row and leaves it alone.
     */
    @Test
    void anIssuingRowReclaimedDuringTheSweepIsLeftToItsWorker() {
        StripePurchase row = persist("evt_reclaimed", "pi_reclaimed", Status.ISSUING, NOW.minus(Duration.ofHours(3)));
        row.setClaimedUntil(NOW.minus(Duration.ofMinutes(61)));
        purchases.save(row);
        stripe.onRead = () -> purchases.findByEventId("evt_reclaimed").ifPresent(fresh -> {
            fresh.setClaimedUntil(NOW.plus(Duration.ofMinutes(10)));
            fresh.setClaimedBy("issuer-2");
            purchases.save(fresh);
        });
        stripe.page();

        reconciler().run(NOW);

        StripePurchase after = purchases.findByEventId("evt_reclaimed").orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Status.ISSUING);
        assertThat(after.getClaimedBy()).isEqualTo("issuer-2");
    }

    /**
     * Fail closed: Stripe refusing to answer about a BLOCKED row (deauthorized account, deleted
     * intent) parks it with that said, instead of leaving it invisible and re-read forever. Parking
     * never makes a row mintable.
     */
    @Test
    void aStaleRowStripeWillNotAnswerAboutIsParkedSayingSo() {
        persist("evt_unreadable", "pi_unreadable", Status.BLOCKED, NOW.minus(Duration.ofDays(1)));
        stripe.failPaymentReads = true;
        stripe.page();

        reconciler().run(NOW);

        StripePurchase parked = purchases.findByEventId("evt_unreadable").orElseThrow();
        assertThat(parked.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
        assertThat(parked.getAttentionReason()).isEqualTo(AttentionReason.ISSUE_BLOCKED);
        assertThat(parked.getAttentionDetail()).contains("live check FAILED");
    }

    /**
     * #286: a parked row whose refund webhook was missed gets its refund recorded by the run, before
     * an operator looks at it. Facts only: the row stays parked for the operator.
     */
    @Test
    void aParkedRowsMissedRefundIsRecordedAndTheRowStaysParked() {
        parked("evt_parked", "pi_parked", NOW.minus(Duration.ofHours(3)));
        stripe.live.put("pi_parked", new PaymentState("ch_parked", 500L, 500L, false));
        stripe.page();

        reconciler().run(NOW);

        StripePurchase row = purchases.findByEventId("evt_parked").orElseThrow();
        assertThat(row.getRefundedAmountMinor()).isEqualTo(500L);
        assertThat(row.getReversedAt()).isNotNull();
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
        assertThat(row.getAttentionReason()).isEqualTo(AttentionReason.STALE_ISSUING);
    }

    /** #286: a missed dispute on a parked row is recorded the same way. */
    @Test
    void aParkedRowsMissedDisputeIsRecorded() {
        parked("evt_disputed", "pi_disputed", NOW.minus(Duration.ofHours(3)));
        stripe.live.put("pi_disputed", new PaymentState("ch_disputed", 500L, 0L, true));
        stripe.page();

        reconciler().run(NOW);

        assertThat(purchases.findByEventId("evt_disputed").orElseThrow().isDisputed()).isTrue();
    }

    /**
     * A re-read that finds nothing new writes nothing: recording again would bump reversed_at and
     * re-raise a reversal the operator already acknowledged.
     */
    @Test
    void aParkedRowWhoseFactsAreKnownIsNotRewritten() {
        StripePurchase row = parked("evt_known", "pi_known", NOW.minus(Duration.ofHours(3)));
        row.setRefundedAmountMinor(500L);
        row.setReversedAt(NOW.minus(Duration.ofHours(2)));
        entityManager.persistAndFlush(row);
        stripe.live.put("pi_known", new PaymentState("ch_known", 500L, 500L, false));
        stripe.page();

        reconciler().run(NOW);

        assertThat(purchases.findByEventId("evt_known").orElseThrow().getReversedAt())
                .isEqualTo(NOW.minus(Duration.ofHours(2)));
    }

    /** Bounded and rotating: each run reads one page of parked rows, and the next run the next page. */
    @Test
    void parkedRowsAreReadInBoundedRotatingPages() {
        parkedBatch = 2;
        for (int i = 0; i < 3; i++) {
            parked("evt_p" + i, "pi_p" + i, NOW.minus(Duration.ofHours(10 - i)));
        }
        stripe.page();
        StripePurchaseReconciler reconciler = reconciler();

        reconciler.run(NOW);
        int firstRun = stripe.paymentReads;
        reconciler.run(NOW.plusSeconds(600));

        assertThat(firstRun).isEqualTo(2);
        assertThat(stripe.paymentReads).isEqualTo(3);
    }

    /** Stripe failing on a parked row changes nothing on it, and the run carries on. */
    @Test
    void aParkedRowStripeWillNotAnswerAboutIsLeftAsItIs() {
        parked("evt_unread", "pi_unread", NOW.minus(Duration.ofHours(3)));
        stripe.failPaymentReads = true;
        stripe.page();

        reconciler().run(NOW);

        StripePurchase row = purchases.findByEventId("evt_unread").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
        assertThat(row.getRefundedAmountMinor()).isNull();
    }

    private StripePurchase parked(String eventId, String pi, Instant created) {
        StripePurchase row = persist(eventId, pi, Status.NEEDS_ATTENTION, created);
        row.setAttentionReason(AttentionReason.STALE_ISSUING);
        row.setAttentionAt(created);
        return entityManager.persistFlushFind(row);
    }

    private void cursorRanAt(String accountId, Instant lastRun) {
        StripeReconcileCursor cursor = new StripeReconcileCursor();
        cursor.setAccountId(accountId);
        cursor.setCreatedThrough(NOW.minus(Duration.ofDays(1)));
        cursor.setLastRunAt(lastRun);
        cursor.setUpdatedAt(NOW);
        cursors.save(cursor);
    }

    // ------------------------------------------------------------------ fixtures

    private StripePurchase persist(String eventId, String pi, Status status, Instant created) {
        StripePurchase p = new StripePurchase();
        p.setEventId(eventId);
        p.setCheckoutSessionId("cs_" + eventId);
        p.setPaymentIntentId(pi);
        p.setPaymentRequestId(UUID.randomUUID().toString());
        p.setConnectedAccountId(ACCOUNT);
        p.setAmountMinor(500L);
        p.setCurrency("gbp");
        p.setStatus(status);
        p.setCreatedAt(created);
        p.setUpdatedAt(created);
        return entityManager.persistFlushFind(p);
    }

    private static CompletedSession paid(String id, String pi) {
        return session(id, pi, "paid");
    }

    private static CompletedSession session(String id, String pi, String paymentStatus) {
        return new CompletedSession(id, NOW.minus(Duration.ofHours(2)), paymentStatus, pi, 500L, "gbp",
                Map.of("buyer_pubkey", BUYER), false);
    }

    record Window(String accountId, Instant from, Instant until, String startingAfter) {
    }

    /** Stripe, scripted: one page, live states by pi_ (intact unless set). */
    static final class ScriptedStripe implements StripeReconcileSource {
        final List<Window> windows = new ArrayList<>();
        final Map<String, PaymentState> live = new HashMap<>();
        /** Successive answers for one pi_ (the last repeats): a refund landing between two reads. */
        final Map<String, List<PaymentState>> liveSequence = new HashMap<>();
        Runnable onRead = () -> { };
        List<CompletedSession> sessions = List.of();
        boolean hasMore;
        boolean failListing;
        boolean failPaymentReads;
        int paymentReads;

        void page(CompletedSession... page) {
            sessions = List.of(page);
        }

        @Override
        public SessionPage completeSessions(String accountId, Instant from, Instant until, String startingAfter,
                int limit) {
            if (failListing) {
                throw new StripeReconcileException("Stripe down", null, false);
            }
            windows.add(new Window(accountId, from, until, startingAfter));
            return new SessionPage(sessions, hasMore);
        }

        @Override
        public PaymentState paymentState(String accountId, String paymentIntentId) {
            paymentReads++;
            if (failPaymentReads) {
                throw new StripeReconcileException("No such payment_intent", null, false);
            }
            onRead.run();
            List<PaymentState> sequence = liveSequence.get(paymentIntentId);
            if (sequence != null) {
                return sequence.size() > 1 ? sequence.remove(0) : sequence.get(0);
            }
            return live.getOrDefault(paymentIntentId, new PaymentState("ch_" + paymentIntentId, 500L, 0L, false));
        }
    }
}
