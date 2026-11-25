package xyz.tcheeric.payment.adapter.paypal.webhook.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.PaypalCheckout;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalDispute;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.Outcome;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.ParkedRows;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.ReconcileBudget;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StaleRows;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileException;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;

class PaypalPurchaseReconcilerTest {

    static final Instant NOW = Instant.parse("2027-01-01T12:00:00Z");

    PaypalCheckoutRepository checkouts;
    PaypalOrdersClient paypal;
    PaypalCaptureService captures;
    StaleRows stale;
    ParkedRows parked;
    PaypalPurchaseReconciler reconciler;

    @BeforeEach
    void setUp() {
        checkouts = mock(PaypalCheckoutRepository.class);
        paypal = mock(PaypalOrdersClient.class);
        captures = mock(PaypalCaptureService.class);
        stale = mock(StaleRows.class);
        parked = mock(ParkedRows.class);
        when(stale.newBudget()).thenReturn(new ReconcileBudget(5));
        when(parked.newBudget()).thenReturn(new ReconcileBudget(5));
        reconciler = new PaypalPurchaseReconciler(checkouts, paypal, captures, stale, parked,
                new PaypalPurchaseReconciler.Settings(true, Duration.ofDays(30), Duration.ofMinutes(10),
                        Duration.ofHours(6), 50, 3));
    }

    @Test
    void onlyOpenOrdersBetweenLookbackAndGraceAreRead() {
        when(checkouts.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any())).thenReturn(List.of());

        reconciler.run(NOW);

        verify(checkouts).findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(eq(PaypalCheckout.Status.CREATED),
                eq(NOW.minus(Duration.ofDays(30))), eq(NOW.minus(Duration.ofMinutes(10))), any());
        verify(stale).sweep(eq(NOW), any());
        verify(parked).sweep(any());
    }

    @Test
    void aLostCaptureIsRecordedLate() {
        PaypalCheckout open = open("O1", Duration.ofMinutes(30));
        PaypalOrder order = order("O1", "COMPLETED");
        when(checkouts.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any())).thenReturn(List.of(open));
        when(paypal.getOrder("SELLER", "O1")).thenReturn(order);
        when(captures.settle(open, order, true)).thenReturn(Outcome.RECORDED);

        reconciler.run(NOW);

        verify(captures).settle(open, order, true);
        verify(captures, never()).abandon(any(), anyString());
    }

    @Test
    void anOrderNeverApprovedIsAbandonedOnlyAfterTheCutoff() {
        PaypalCheckout young = open("O1", Duration.ofHours(1));
        PaypalCheckout old = open("O2", Duration.ofHours(7));
        when(checkouts.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any()))
                .thenReturn(List.of(young, old));
        when(paypal.getOrder(eq("SELLER"), anyString())).thenReturn(order("O", "PAYER_ACTION_REQUIRED"));
        when(captures.settle(any(), any(), anyBoolean())).thenReturn(Outcome.NOT_APPROVED);

        reconciler.run(NOW);

        verify(captures, never()).abandon(eq(young), anyString());
        verify(captures).abandon(eq(old), anyString());
    }

    @Test
    void anOrderPaypalForgotIsAbandoned() {
        PaypalCheckout open = open("O1", Duration.ofMinutes(30));
        when(checkouts.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any())).thenReturn(List.of(open));
        when(paypal.getOrder("SELLER", "O1")).thenThrow(new PaypalApiException("gone", 404, "RESOURCE_NOT_FOUND", null, null));

        reconciler.run(NOW);

        verify(captures).abandon(eq(open), anyString());
    }

    @Test
    void oneFailingOrderDoesNotStopTheRest() {
        PaypalCheckout a = open("O1", Duration.ofMinutes(30));
        PaypalCheckout b = open("O2", Duration.ofMinutes(30));
        when(checkouts.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any())).thenReturn(List.of(a, b));
        when(paypal.getOrder("SELLER", "O1")).thenThrow(new PaypalApiException("down", 500, null, null, null));
        when(paypal.getOrder("SELLER", "O2")).thenReturn(order("O2", "COMPLETED"));

        reconciler.run(NOW);

        verify(captures).settle(eq(b), any(), eq(true));
    }

    @Test
    void aRateLimitEndsTheRunBeforeTheSweeps() {
        PaypalCheckout a = open("O1", Duration.ofMinutes(30));
        PaypalCheckout b = open("O2", Duration.ofMinutes(30));
        when(checkouts.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any())).thenReturn(List.of(a, b));
        when(paypal.getOrder("SELLER", "O1")).thenThrow(new PaypalApiException("slow down", 429, null, null, null));

        reconciler.run(NOW);

        verify(paypal, times(1)).getOrder(anyString(), anyString());
        verify(stale, never()).sweep(any(), any());
        verify(parked, never()).sweep(any());
    }

    @Test
    void theCallBudgetBoundsARun() {
        List<PaypalCheckout> many = List.of(open("O1", Duration.ofMinutes(30)), open("O2", Duration.ofMinutes(30)),
                open("O3", Duration.ofMinutes(30)), open("O4", Duration.ofMinutes(30)));
        when(checkouts.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any())).thenReturn(many);
        when(paypal.getOrder(anyString(), anyString())).thenReturn(order("O", "COMPLETED"));

        reconciler.run(NOW);

        verify(paypal, times(3)).getOrder(anyString(), anyString());
    }

    @Test
    void disabledDoesNothing() {
        PaypalPurchaseReconciler off = new PaypalPurchaseReconciler(checkouts, paypal, captures, stale, parked,
                new PaypalPurchaseReconciler.Settings(false, Duration.ofDays(30), Duration.ofMinutes(10),
                        Duration.ofHours(6), 50, 3));
        off.tick();
        verify(checkouts, never()).findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any());
    }

    @Test
    void settingsRefuseNonsense() {
        assertThatThrownBy(() -> new PaypalPurchaseReconciler.Settings(true, Duration.ofDays(1), Duration.ZERO,
                Duration.ofHours(1), 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStateReaderSumsRefundsAndSeesOpenDisputes() {
        PaypalPaymentStateReader reader = new PaypalPaymentStateReader(paypal);
        when(paypal.getCapture("SELLER", "C1"))
                .thenReturn(new PaypalCapture("C1", "PARTIALLY_REFUNDED", 1000L, "usd", null, "O1", "SELLER"));
        when(paypal.getOrder("SELLER", "O1"))
                .thenReturn(new PaypalOrder("O1", "COMPLETED", null, null, "SELLER", 1000L, "usd", List.of(), 400L));
        when(paypal.disputesFor("SELLER", "C1")).thenReturn(List.of(new PaypalDispute("D1", "UNDER_REVIEW")));

        PaymentState state = reader.paymentState("SELLER", "C1");

        assertThat(state.amountMinor()).isEqualTo(1000L);
        assertThat(state.refundedMinor()).isEqualTo(400L);
        assertThat(state.disputed()).isTrue();
        assertThat(state.intact()).isFalse();
    }

    @Test
    void aReversedCaptureIsFullyRefunded() {
        PaypalPaymentStateReader reader = new PaypalPaymentStateReader(paypal);
        when(paypal.getCapture("SELLER", "C1"))
                .thenReturn(new PaypalCapture("C1", "REVERSED", 1000L, "usd", null, null, "SELLER"));
        when(paypal.disputesFor("SELLER", "C1")).thenReturn(List.of(new PaypalDispute("D1", "RESOLVED")));

        PaymentState state = reader.paymentState("SELLER", "C1");

        assertThat(state.refundedMinor()).isEqualTo(1000L);
        assertThat(state.disputed()).isFalse();
    }

    @Test
    void aRateLimitedReadSaysSo() {
        PaypalPaymentStateReader reader = new PaypalPaymentStateReader(paypal);
        when(paypal.getCapture("SELLER", "C1")).thenThrow(new PaypalApiException("slow", 429, null, null, null));

        assertThatThrownBy(() -> reader.paymentState("SELLER", "C1")).isInstanceOfSatisfying(
                StripeReconcileException.class, e -> assertThat(e.isRateLimited()).isTrue());
    }

    static PaypalCheckout open(String orderId, Duration age) {
        PaypalCheckout c = new PaypalCheckout();
        c.setOrderId(orderId);
        c.setPaypalMerchantId("SELLER");
        c.setStatus(PaypalCheckout.Status.CREATED);
        c.setCreatedAt(NOW.minus(age));
        return c;
    }

    static PaypalOrder order(String id, String status) {
        return new PaypalOrder(id, status, null, null, "SELLER", 1000L, "usd", List.of(), 0);
    }
}
