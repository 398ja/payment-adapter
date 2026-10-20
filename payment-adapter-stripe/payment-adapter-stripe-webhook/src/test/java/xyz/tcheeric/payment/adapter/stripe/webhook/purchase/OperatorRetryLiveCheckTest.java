package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.AttentionReason;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.OperatorResolution.Applied;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.OperatorResolution.Outcome;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.OperatorResolution.Refused;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.OperatorResolution.Unverifiable;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseDischargeService.ResolveAction;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseDischargeService.ResolveResult;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeLivePaymentCheck;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileException;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource;

/**
 * payment-adapter#286 item 3: an operator's {@code retry} of a parked purchase re-reads the payment
 * live from Stripe, on the stall's connected account, before the row can return to OWED (a mint) or
 * ISSUED (a delivery). The real {@link RecordingPurchaseListener} records what Stripe says, so a refund
 * found here lands on the row exactly as the {@code charge.refunded} webhook would have put it.
 */
class OperatorRetryLiveCheckTest {

    private static final String EVENT = "evt_parked";
    private static final String ACCOUNT = "acct_stall1";
    private static final String PAYMENT_INTENT = "pi_parked";

    private final ScriptedStripe stripe = new ScriptedStripe();
    private StripePurchaseRepository purchases;
    private StripePurchase row;
    private OperatorResolution resolutions;

    @BeforeEach
    void setUp() {
        purchases = mock(StripePurchaseRepository.class);
        row = new StripePurchase();
        row.setEventId(EVENT);
        row.setConnectedAccountId(ACCOUNT);
        row.setPaymentIntentId(PAYMENT_INTENT);
        row.setAmountMinor(500L);
        row.setCurrency("gbp");
        park(AttentionReason.STALE_ISSUING, null);
        when(purchases.findByEventId(EVENT)).thenReturn(Optional.of(row));
        when(purchases.findByPaymentIntentId(PAYMENT_INTENT)).thenReturn(Optional.of(row));
        when(purchases.save(any())).thenAnswer(i -> i.getArgument(0));
        RecordingPurchaseListener listener =
                new RecordingPurchaseListener(purchases, mock(ConnectedStripeAccountRepository.class));
        PurchaseDischargeService discharges = new PurchaseDischargeService(purchases, mock(GatewayFulfilmentClient.class));
        resolutions = new OperatorResolution(purchases, discharges, new StripeLivePaymentCheck(stripe, listener));
    }

    /** Clean: Stripe shows the money where the buyer paid it, so the retry goes ahead to OWED. */
    @Test
    void aCleanPaymentIsRetriedToOwed() {
        Outcome outcome = retry();

        assertThat(outcome).isEqualTo(new Applied(ResolveResult.RESOLVED));
        assertThat(row.getStatus()).isEqualTo(Status.OWED);
        assertThat(row.getRefundedAmountMinor()).isNull();
        assertThat(stripe.reads).containsExactly(ACCOUNT + "/" + PAYMENT_INTENT);
    }

    /**
     * Refund found: the webhook was missed, so the row says nothing about it. The refund is recorded
     * (refunded_amount_minor, reversed_at) and the retry refused with the reason; nothing is minted.
     */
    @Test
    void aRefundTheRowDidNotKnowIsRecordedAndTheRetryRefused() {
        stripe.answer = new StripeReconcileSource.PaymentState("ch_1", 500L, 500L, false);

        Outcome outcome = retry();

        assertThat(outcome).isInstanceOfSatisfying(Refused.class, refused -> {
            assertThat(refused.refusal().reason()).isEqualTo("refunded");
            assertThat(refused.refusal().detail()).contains("refunded 500 of 500", "now recorded");
        });
        assertThat(row.getRefundedAmountMinor()).isEqualTo(500L);
        assertThat(row.getReversedAt()).isNotNull();
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
    }

    /** A second retry, the refund now on the row, closes it as REFUNDED (#282), which never mints. */
    @Test
    void aRetryAfterTheRefundIsRecordedClosesTheRowAsRefunded() {
        stripe.answer = new StripeReconcileSource.PaymentState("ch_1", 500L, 500L, false);
        retry();

        Outcome outcome = retry();

        assertThat(outcome).isEqualTo(new Applied(ResolveResult.RESOLVED));
        assertThat(row.getStatus()).isEqualTo(Status.REFUNDED);
    }

    /** A partial refund is still a refund: recorded at its amount, and the retry refused. */
    @Test
    void aPartialRefundIsRecordedAndTheRetryRefused() {
        stripe.answer = new StripeReconcileSource.PaymentState("ch_1", 500L, 200L, false);

        assertThat(retry()).isInstanceOf(Refused.class);
        assertThat(row.getRefundedAmountMinor()).isEqualTo(200L);
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
    }

    /** A coupon exists and the money went back: retrying would deliver a coupon nobody paid for. */
    @Test
    void aRefundAfterIssueRefusesTheRetryEvenWhenAlreadyKnown() {
        park(AttentionReason.DELIVERY_EXHAUSTED, "v1");
        row.setRefundedAmountMinor(500L);
        stripe.answer = new StripeReconcileSource.PaymentState("ch_1", 500L, 500L, false);

        Outcome outcome = retry();

        assertThat(outcome).isInstanceOfSatisfying(Refused.class,
                refused -> assertThat(refused.refusal().reason()).isEqualTo("refunded_after_issue"));
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
    }

    /** Dispute found: recorded on the row, and the retry refused, because a dispute can still be lost. */
    @Test
    void aDisputeIsRecordedAndTheRetryRefused() {
        stripe.answer = new StripeReconcileSource.PaymentState("ch_1", 500L, 0L, true);

        Outcome outcome = retry();

        assertThat(outcome).isInstanceOfSatisfying(Refused.class,
                refused -> assertThat(refused.refusal().reason()).isEqualTo("disputed"));
        assertThat(row.isDisputed()).isTrue();
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
    }

    /** Stripe down: fail closed. Nothing moves, nothing is written, and the operator is told why. */
    @Test
    void anUnreachableStripeFailsClosed() {
        stripe.down = true;

        Outcome outcome = retry();

        assertThat(outcome).isInstanceOfSatisfying(Unverifiable.class,
                unverifiable -> assertThat(unverifiable.detail()).contains("nothing changed"));
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
        assertThat(row.getAttentionReason()).isEqualTo(AttentionReason.STALE_ISSUING);
        verify(purchases, never()).save(any());
    }

    /** With Stripe not configured at all, a retry is never verified, so it never happens. */
    @Test
    void anUnconfiguredStripeFailsClosed() {
        PurchaseDischargeService discharges = new PurchaseDischargeService(purchases, mock(GatewayFulfilmentClient.class));
        OperatorResolution unconfigured = new OperatorResolution(purchases, discharges, LivePaymentCheck.unconfigured());

        assertThat(unconfigured.resolve(EVENT, ResolveAction.RETRY, null, "ops", null)).isInstanceOf(Unverifiable.class);
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
    }

    /** A row naming no PaymentIntent cannot be checked, so it cannot be retried. */
    @Test
    void aRowWithNoPaymentIntentIsRefused() {
        row.setPaymentIntentId(null);

        assertThat(retry()).isInstanceOfSatisfying(Refused.class,
                refused -> assertThat(refused.refusal().reason()).isEqualTo("no_payment_intent"));
        assertThat(stripe.reads).isEmpty();
    }

    /** Only a retry of a parked row is checked: other actions and other rows cost no Stripe call. */
    @Test
    void onlyARetryOfAParkedRowReadsStripe() {
        resolutions.resolve(EVENT, ResolveAction.ACKNOWLEDGE, null, "ops", null);
        row.setStatus(Status.OWED);
        resolutions.resolve(EVENT, ResolveAction.RETRY, null, "ops", null);

        assertThat(stripe.reads).isEmpty();
    }

    /** The controller answers a refused retry 409 with its reason, and an unreadable Stripe 503. */
    @Test
    void theControllerSaysWhyARetryWasRefused() {
        PurchaseController controller = new PurchaseController(purchases, mock(PurchaseDischargeService.class),
                resolutions);
        stripe.answer = new StripeReconcileSource.PaymentState("ch_1", 500L, 0L, true);

        ResponseEntity<?> refused = controller.resolve(EVENT, new PurchaseController.ResolveRequest("retry", null, "ops", null));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).isInstanceOfSatisfying(RetryRefusal.class, body -> {
            assertThat(body.error()).isEqualTo("retry_refused");
            assertThat(body.reason()).isEqualTo("disputed");
        });

        stripe.down = true;
        ResponseEntity<?> down = controller.resolve(EVENT, new PurchaseController.ResolveRequest("retry", null, "ops", null));
        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(down.getBody()).isInstanceOfSatisfying(Map.class,
                body -> assertThat(body.get("error")).isEqualTo("stripe_unavailable"));
    }

    /**
     * Review of #296: the live read said intact, but the dispute webhook landed while Stripe was being
     * read. The resolve transaction sees the recorded dispute and refuses, so nothing is minted.
     */
    @Test
    void aDisputeRecordedDuringTheLiveReadStillStopsTheRetry() {
        stripe.duringRead = () -> row.setDisputed(true);

        Outcome outcome = retry();

        assertThat(outcome).isEqualTo(new Applied(ResolveResult.NOT_ALLOWED));
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
    }

    /** Same window for a refund on a minted row: the coupon is not delivered. */
    @Test
    void aRefundAfterIssueRecordedDuringTheLiveReadStillStopsTheRetry() {
        park(AttentionReason.DELIVERY_EXHAUSTED, "v1");
        stripe.duringRead = () -> row.setRefundedAmountMinor(500L);

        Outcome outcome = retry();

        assertThat(outcome).isEqualTo(new Applied(ResolveResult.NOT_ALLOWED));
        assertThat(row.getStatus()).isEqualTo(Status.NEEDS_ATTENTION);
    }

    /** A refund before issue recorded during the read closes the row REFUNDED, never OWED. */
    @Test
    void aRefundBeforeIssueRecordedDuringTheLiveReadClosesTheRowAsRefunded() {
        stripe.duringRead = () -> row.setRefundedAmountMinor(500L);

        retry();

        assertThat(row.getStatus()).isEqualTo(Status.REFUNDED);
    }

    private Outcome retry() {
        return resolutions.resolve(EVENT, ResolveAction.RETRY, null, "ops", "checked");
    }

    private void park(AttentionReason reason, String voucherId) {
        row.setStatus(Status.NEEDS_ATTENTION);
        row.setAttentionReason(reason);
        row.setAttentionAt(Instant.now());
        row.setVoucherId(voucherId);
    }

    /** Stripe, scripted: one answer for the PaymentIntent, or down. Records which account was asked. */
    private static final class ScriptedStripe implements StripeReconcileSource {
        PaymentState answer = new PaymentState("ch_1", 500L, 0L, false);
        boolean down;
        /** A webhook landing on the row while Stripe is being read. */
        Runnable duringRead = () -> { };
        final List<String> reads = new ArrayList<>();

        @Override
        public SessionPage completeSessions(String accountId, Instant from, Instant until, String startingAfter,
                int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentState paymentState(String accountId, String paymentIntentId) {
            if (down) {
                throw new StripeReconcileException("Could not read PaymentIntent: connection refused", null, false);
            }
            reads.add(accountId + "/" + paymentIntentId);
            duringRead.run();
            return answer;
        }
    }
}
