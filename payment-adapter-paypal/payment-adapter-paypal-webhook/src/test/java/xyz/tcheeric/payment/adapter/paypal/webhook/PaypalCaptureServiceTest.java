package xyz.tcheeric.payment.adapter.paypal.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.PaypalCheckout;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.Outcome;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.PaypalCaptureException;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.PaymentStateReader;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.PaidPurchase;

class PaypalCaptureServiceTest {

    static final String SELLER = "SELLER123";
    static final String ORDER = "5O190127TN364715T";
    static final String CAPTURE = "3C679366HH908993F";

    PaypalCheckoutRepository checkouts;
    ConnectedPaypalAccountRepository accounts;
    PaypalOrdersClient orders;
    StripePurchaseListener listener;
    PaymentStateReader reader;
    PaypalCaptureService service;
    PaypalCheckout checkout;

    @BeforeEach
    void setUp() {
        checkouts = mock(PaypalCheckoutRepository.class);
        accounts = mock(ConnectedPaypalAccountRepository.class);
        orders = mock(PaypalOrdersClient.class);
        listener = mock(StripePurchaseListener.class);
        reader = mock(PaymentStateReader.class);
        service = new PaypalCaptureService(checkouts, accounts, orders, listener, reader, new ObjectMapper());
        checkout = checkout();
        when(checkouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void anApprovedOrderIsCapturedAndRecordedFromOurOwnRow() throws Exception {
        when(orders.captureOrder(SELLER, ORDER, "paypal:capture:" + ORDER)).thenReturn(captured("COMPLETED", 1250L));

        assertThat(service.capture(checkout, false)).isEqualTo(Outcome.RECORDED);

        PaidPurchase paid = paid();
        assertThat(paid.eventId()).isEqualTo("paypal:" + CAPTURE);
        assertThat(paid.checkoutSessionId()).isEqualTo(ORDER);
        assertThat(paid.paymentIntentId()).isEqualTo(CAPTURE);
        assertThat(paid.connectedAccountId()).isEqualTo(SELLER);
        assertThat(paid.processor()).isEqualTo(StripePurchase.PROCESSOR_PAYPAL);
        assertThat(paid.stallPubkey()).isEqualTo("npub-stall");
        assertThat(paid.metadata()).containsEntry("buyer_pubkey", "npub-buyer").containsEntry("imani_env", "test");
        assertThat(checkout.getStatus()).isEqualTo(PaypalCheckout.Status.CAPTURED);
        assertThat(checkout.getCaptureId()).isEqualTo(CAPTURE);
        verify(reader, never()).paymentState(anyString(), anyString());
    }

    @Test
    void aCapturedCheckoutIsNeverCapturedAgain() {
        checkout.setStatus(PaypalCheckout.Status.CAPTURED);
        assertThat(service.capture(checkout, false)).isEqualTo(Outcome.RECORDED);
        verify(orders, never()).captureOrder(anyString(), anyString(), anyString());
    }

    @Test
    void capturedByAnotherCallerReadsTheOrderInstead() throws Exception {
        when(orders.captureOrder(anyString(), anyString(), anyString()))
                .thenThrow(new PaypalApiException("dup", 422, "ORDER_ALREADY_CAPTURED", null, null));
        when(orders.getOrder(SELLER, ORDER)).thenReturn(captured("COMPLETED", 1250L));

        assertThat(service.capture(checkout, false)).isEqualTo(Outcome.RECORDED);
        assertThat(paid().paymentIntentId()).isEqualTo(CAPTURE);
    }

    @Test
    void capturedElsewhereButUnreadableStaysOpenForTheReconciler() {
        when(orders.captureOrder(anyString(), anyString(), anyString()))
                .thenThrow(new PaypalApiException("dup", 422, "ORDER_ALREADY_CAPTURED", null, null));
        when(orders.getOrder(SELLER, ORDER)).thenThrow(new PaypalApiException("forbidden", 403, null, null, null));

        assertThat(service.capture(checkout, false)).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(checkout.getStatus()).isEqualTo(PaypalCheckout.Status.CREATED);
    }

    @Test
    void notYetApprovedChangesNothing() {
        when(orders.captureOrder(anyString(), anyString(), anyString()))
                .thenThrow(new PaypalApiException("no", 422, "ORDER_NOT_APPROVED", null, null));

        assertThat(service.capture(checkout, false)).isEqualTo(Outcome.NOT_APPROVED);
        assertThat(checkout.getStatus()).isEqualTo(PaypalCheckout.Status.CREATED);
    }

    @Test
    void aDeclinedInstrumentLeavesTheOrderOpenForAnotherCard() {
        when(orders.captureOrder(anyString(), anyString(), anyString()))
                .thenThrow(new PaypalApiException("no", 422, "INSTRUMENT_DECLINED", null, null));

        assertThat(service.capture(checkout, false)).isEqualTo(Outcome.DECLINED);
        assertThat(checkout.getStatus()).isEqualTo(PaypalCheckout.Status.CREATED);
        assertThat(checkout.getLastError()).contains("INSTRUMENT_DECLINED");
    }

    @Test
    void anOutageChangesNothing() {
        when(orders.captureOrder(anyString(), anyString(), anyString()))
                .thenThrow(new PaypalApiException("down", 503, null, null, null));

        assertThat(service.capture(checkout, false)).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(checkout.getStatus()).isEqualTo(PaypalCheckout.Status.CREATED);
    }

    @Test
    void aFinalRefusalClosesTheCheckout() {
        when(orders.captureOrder(anyString(), anyString(), anyString()))
                .thenThrow(new PaypalApiException("no", 422, "PAYEE_ACCOUNT_RESTRICTED", null, null));

        assertThat(service.capture(checkout, false)).isEqualTo(Outcome.DECLINED);
        assertThat(checkout.getStatus()).isEqualTo(PaypalCheckout.Status.FAILED);
    }

    @Test
    void aPendingCaptureIsNotOwedYet() throws Exception {
        when(orders.captureOrder(anyString(), anyString(), anyString())).thenReturn(captured("PENDING", 1250L));

        assertThat(service.capture(checkout, false)).isEqualTo(Outcome.PENDING);
        assertThat(checkout.getCaptureId()).isEqualTo(CAPTURE);
        assertThat(checkout.getStatus()).isEqualTo(PaypalCheckout.Status.CREATED);
        verify(listener, never()).onPurchasePaid(any());
    }

    @Test
    void aLateCaptureAppliesARefundThatHappenedBeforeTheRowExisted() throws Exception {
        when(orders.captureOrder(anyString(), anyString(), anyString())).thenReturn(captured("COMPLETED", 1250L));
        when(reader.paymentState(SELLER, CAPTURE)).thenReturn(new PaymentState(CAPTURE, 1250L, 1250L, false));

        assertThat(service.capture(checkout, true)).isEqualTo(Outcome.RECORDED);

        verify(listener).onPurchaseReversed(any());
    }

    @Test
    void aListenerFailureIsNotSuccess() throws Exception {
        when(orders.captureOrder(anyString(), anyString(), anyString())).thenReturn(captured("COMPLETED", 1250L));
        doThrow(new IllegalStateException("db down")).when(listener).onPurchasePaid(any());

        assertThatThrownBy(() -> service.capture(checkout, false)).isInstanceOf(PaypalCaptureException.class);
        assertThat(checkout.getStatus()).isEqualTo(PaypalCheckout.Status.CREATED);
    }

    @Test
    void anOrphanIsRecordedOnlyForOneOfOurSellers() throws Exception {
        ConnectedPaypalAccount seller = new ConnectedPaypalAccount();
        seller.setMerchantPubkey("npub-stall");
        when(accounts.findByPaypalMerchantId(SELLER)).thenReturn(Optional.of(seller));
        PaypalCapture ours = new PaypalCapture(CAPTURE, "COMPLETED", 500L, "usd", null, ORDER, SELLER);
        PaypalCapture theirs = new PaypalCapture("9X", "COMPLETED", 500L, "usd", null, "O2", "STRANGER");

        assertThat(service.recordOrphan(ours, false)).isTrue();
        assertThat(service.recordOrphan(theirs, false)).isFalse();
        PaidPurchase paid = paid();
        assertThat(paid.metadata()).isEmpty();
        assertThat(paid.stallPubkey()).isEqualTo("npub-stall");
    }

    @Test
    void abandonClosesOnlyAnOpenCheckout() {
        service.abandon(checkout, "never approved");
        assertThat(checkout.getStatus()).isEqualTo(PaypalCheckout.Status.FAILED);

        PaypalCheckout done = checkout();
        done.setStatus(PaypalCheckout.Status.CAPTURED);
        service.abandon(done, "never approved");
        assertThat(done.getStatus()).isEqualTo(PaypalCheckout.Status.CAPTURED);
    }

    @Test
    void settlingAnApprovedOrderWithoutACaptureCapturesIt() {
        when(orders.captureOrder(eq(SELLER), eq(ORDER), anyString())).thenReturn(captured("COMPLETED", 1250L));
        when(reader.paymentState(SELLER, CAPTURE)).thenReturn(new PaymentState(CAPTURE, 1250L, 0L, false));
        PaypalOrder approved = new PaypalOrder(ORDER, "APPROVED", null, "test:q-1", SELLER, 1250L, "usd", List.of(), 0);

        assertThat(service.settle(checkout, approved, true)).isEqualTo(Outcome.RECORDED);
    }

    private PaidPurchase paid() throws Exception {
        ArgumentCaptor<PaidPurchase> captor = ArgumentCaptor.forClass(PaidPurchase.class);
        verify(listener).onPurchasePaid(captor.capture());
        return captor.getValue();
    }

    static PaypalOrder captured(String status, Long amount) {
        PaypalCapture capture = new PaypalCapture(CAPTURE, status, amount, "usd", "test:q-1", ORDER, SELLER);
        return new PaypalOrder(ORDER, "COMPLETED", null, "test:q-1", SELLER, amount, "usd", List.of(capture), 0);
    }

    static PaypalCheckout checkout() {
        PaypalCheckout c = new PaypalCheckout();
        c.setOrderId(ORDER);
        c.setQuoteId("q-1");
        c.setStallPubkey("npub-stall");
        c.setPaypalMerchantId(SELLER);
        c.setAmountMinor(1250L);
        c.setCurrency("usd");
        c.setEnvironment("test");
        c.setMetadata("{\"buyer_pubkey\":\"npub-buyer\",\"imani_env\":\"test\"}");
        c.setStatus(PaypalCheckout.Status.CREATED);
        return c;
    }
}
