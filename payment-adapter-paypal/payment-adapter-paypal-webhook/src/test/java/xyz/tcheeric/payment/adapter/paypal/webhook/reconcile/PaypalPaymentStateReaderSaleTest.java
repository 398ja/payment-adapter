package xyz.tcheeric.payment.adapter.paypal.webhook.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalDispute;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionsClient;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileException;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;

/** Regression: an instalment row's reference is a sale, which the live read must find rather than fail on. */
class PaypalPaymentStateReaderSaleTest {

    static final String MERCHANT = "SELLER123";
    static final String SALE = "SALE-1";

    PaypalOrdersClient orders;
    PaypalSubscriptionsClient sales;
    PaypalPaymentStateReader reader;

    @BeforeEach
    void setUp() {
        orders = mock(PaypalOrdersClient.class);
        sales = mock(PaypalSubscriptionsClient.class);
        reader = new PaypalPaymentStateReader(orders, sales);
        when(orders.getCapture(MERCHANT, SALE)).thenThrow(new PaypalApiException("no capture", 404,
                "RESOURCE_NOT_FOUND", null, null));
        when(orders.disputesFor(MERCHANT, SALE)).thenReturn(List.of());
    }

    // A reference PayPal knows as a sale, not a capture, is read as that sale.
    @Test
    void aSaleIsReadWhenNoCaptureExists() {
        when(sales.getSale(MERCHANT, SALE)).thenReturn(new PaypalSale(SALE, "completed", 5000L, "gbp", null, Instant.now()));

        PaymentState state = reader.paymentState(MERCHANT, SALE);

        assertThat(state.amountMinor()).isEqualTo(5000L);
        assertThat(state.refunded()).isFalse();
        assertThat(state.disputed()).isFalse();
    }

    // A refunded sale reads as fully refunded; a partial one as refunded at all, so it is never issued as clean.
    @Test
    void refundedSalesAreNeverClean() {
        when(sales.getSale(MERCHANT, SALE)).thenReturn(new PaypalSale(SALE, "refunded", 5000L, "gbp", null, null));
        assertThat(reader.paymentState(MERCHANT, SALE).refundedMinor()).isEqualTo(5000L);

        when(sales.getSale(MERCHANT, SALE)).thenReturn(
                new PaypalSale(SALE, "partially_refunded", 5000L, "gbp", null, null));
        assertThat(reader.paymentState(MERCHANT, SALE).refunded()).isTrue();
    }

    // An open dispute on the sale is reported.
    @Test
    void aDisputedSaleIsReported() {
        when(sales.getSale(MERCHANT, SALE)).thenReturn(new PaypalSale(SALE, "completed", 5000L, "gbp", null, null));
        when(orders.disputesFor(MERCHANT, SALE)).thenReturn(List.of(new PaypalDispute("PP-D-1", "OPEN")));

        assertThat(reader.paymentState(MERCHANT, SALE).disputed()).isTrue();
    }

    // Without the subscriptions client (plans off) a missing capture is still an error, as before.
    @Test
    void withoutPlansAMissingCaptureIsAnError() {
        PaypalPaymentStateReader capturesOnly = new PaypalPaymentStateReader(orders);

        assertThatThrownBy(() -> capturesOnly.paymentState(MERCHANT, SALE))
                .isInstanceOf(StripeReconcileException.class);
    }
}
