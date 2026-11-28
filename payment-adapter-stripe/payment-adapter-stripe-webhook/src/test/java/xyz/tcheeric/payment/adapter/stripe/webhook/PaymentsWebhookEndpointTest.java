package xyz.tcheeric.payment.adapter.stripe.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.webhook.StripeWebhookEndpoint;

/** payment-adapter#286: what the payments endpoint is checked for comes from the handler's registry. */
class PaymentsWebhookEndpointTest {

    /** The refund and dispute events #286 found unsubscribed are among those the check requires. */
    @Test
    void thePaymentsEndpointMustDeliverRefundsAndDisputes() {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setWebhookEndpointId("we_payments");

        StripeWebhookEndpoint endpoint = new PaymentsWebhookEndpointConfiguration().paymentsWebhookEndpoint(properties, false);

        assertThat(endpoint.endpointId()).isEqualTo("we_payments");
        assertThat(endpoint.handledEvents())
                .contains("charge.refunded", "charge.dispute.created", "checkout.session.completed")
                .hasSize(StripePaymentEvent.types(false).size())
                .doesNotContain("invoice.paid");
    }

    /** With instalment plans on, the invoice and subscription events become required; off, they are not. */
    @Test
    void thePlanEventsAreRequiredOnlyWhenPlansAreOn() {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setWebhookEndpointId("we_payments");

        assertThat(new PaymentsWebhookEndpointConfiguration().paymentsWebhookEndpoint(properties, true).handledEvents())
                .contains("invoice.paid", "invoice.payment_failed", "customer.subscription.deleted")
                .hasSize(StripePaymentEvent.values().length);
        assertThat(StripePaymentEvent.types()).doesNotContain("invoice.paid", "customer.subscription.deleted");
    }

    /** An event the handler only acknowledges is not required: subscribing to it would change nothing. */
    @Test
    void anEventTheHandlerIgnoresIsNotRequired() {
        assertThat(StripePaymentEvent.fromType("charge.dispute.funds_withdrawn")).isEmpty();
        assertThat(StripePaymentEvent.types()).doesNotContain("charge.dispute.funds_withdrawn");
    }

    /** imani-wallet#277: a won dispute restores the voucher, so its closing must be delivered. */
    @Test
    void theDisputeClosingIsRequired() {
        assertThat(StripePaymentEvent.types()).contains("charge.dispute.closed");
    }
}
