package xyz.tcheeric.payment.adapter.stripe.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The properties bean is Lombok {@code @Data}, so its generated {@code toString} would print every
 * field. Anything that logs the bean, or renders it into an exception or a debugger dump, would
 * then carry the Stripe secret key.
 */
class StripeGatewayPropertiesToStringTest {

    // The secret key value must not appear when the bean is rendered as a string.
    @Test
    void toStringDoesNotContainTheSecretKey() {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setSecretKey("sk_test_TOSTRINGCANARY123");
        properties.setSuccessUrl("https://example.test/ok");

        String rendered = properties.toString();

        assertThat(rendered).contains("https://example.test/ok");
        assertThat(rendered.contains("sk_test_TOSTRINGCANARY123")).as("secret key in toString").isFalse();
    }
}
