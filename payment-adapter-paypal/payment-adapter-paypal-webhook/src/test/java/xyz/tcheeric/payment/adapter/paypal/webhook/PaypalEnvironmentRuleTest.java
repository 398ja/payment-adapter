package xyz.tcheeric.payment.adapter.paypal.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;

/** An order stamped with another deployment's name is dropped by the ledger: refuse to start. */
class PaypalEnvironmentRuleTest {

    @Test
    void theSameNameStarts() {
        assertThatCode(() -> PaypalWebhookConfiguration.requireSameEnvironment(env("Prod"), "prod"))
                .doesNotThrowAnyException();
        assertThatCode(() -> PaypalWebhookConfiguration.requireSameEnvironment(env(null), ""))
                .doesNotThrowAnyException();
    }

    @Test
    void differentNamesRefuseToStart() {
        assertThatThrownBy(() -> PaypalWebhookConfiguration.requireSameEnvironment(env("staging"), "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PAYPAL_ENVIRONMENT");
        assertThatThrownBy(() -> PaypalWebhookConfiguration.requireSameEnvironment(env(null), "prod"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaypalWebhookConfiguration.requireSameEnvironment(env("prod"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PaypalGatewayProperties env(String name) {
        PaypalGatewayProperties properties = new PaypalGatewayProperties();
        properties.setEnvironment(name);
        return properties;
    }
}
