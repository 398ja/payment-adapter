package xyz.tcheeric.payment.adapter.stripe.connect.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The properties bean is Lombok {@code @Data}, so its generated {@code toString} would print every
 * field. Anything that logs the bean would then carry the Connect webhook signing secret.
 */
class StripeConnectPropertiesToStringTest {

    // The webhook secret value must not appear when the bean is rendered as a string.
    @Test
    void toStringDoesNotContainTheWebhookSecret() {
        StripeConnectProperties properties = new StripeConnectProperties();
        properties.setWebhookSecret("whsec_TOSTRINGCANARY123");
        properties.setCountry("GB");

        String rendered = properties.toString();

        assertThat(rendered).contains("GB");
        assertThat(rendered.contains("whsec_TOSTRINGCANARY123")).as("webhook secret in toString").isFalse();
    }
}
