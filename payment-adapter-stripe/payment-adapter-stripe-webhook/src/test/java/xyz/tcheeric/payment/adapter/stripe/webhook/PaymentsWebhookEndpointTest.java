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

        StripeWebhookEndpoint endpoint = new PaymentsWebhookEndpointConfiguration().paymentsWebhookEndpoint(properties);

        assertThat(endpoint.endpointId()).isEqualTo("we_payments");
        assertThat(endpoint.handledEvents())
                .contains("charge.refunded", "charge.dispute.created", "checkout.session.completed")
                .hasSize(StripePaymentEvent.values().length);
    }

    /** An event the handler only acknowledges is not required: subscribing to it would change nothing. */
    @Test
    void anEventTheHandlerIgnoresIsNotRequired() {
        assertThat(StripePaymentEvent.fromType("charge.dispute.closed")).isEmpty();
        assertThat(StripePaymentEvent.types()).doesNotContain("charge.dispute.closed");
    }
}
