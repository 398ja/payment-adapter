package xyz.tcheeric.payment.adapter.core.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import xyz.tcheeric.payment.adapter.paypal.connect.PaypalConnectController;
import xyz.tcheeric.payment.adapter.paypal.connect.PaypalConnectService;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalPurchaseCheckoutController;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalWebhookHandler;
import xyz.tcheeric.payment.adapter.paypal.webhook.reconcile.PaypalPurchaseReconciler;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.ProcessorLivePaymentCheck;
import xyz.tcheeric.payment.adapter.webhook.core.WebhookRegistry;

/** The rest app with PayPal and its onboarding switched on: every PayPal bean wires (ADR 0002). */
@SpringBootTest(properties = {
        "paypal.enabled=true",
        "paypal.client-id=test-client",
        "paypal.client-secret=test-secret",
        "paypal.success-url=https://wallet.example/buy/done",
        "paypal.cancel-url=https://wallet.example/buy/cancelled",
        "paypal.webhook-id=WH-TEST",
        "paypal.environment=ci",
        "stripe.environment=ci",
        "paypal.connect.enabled=true",
        "paypal.connect.partner-merchant-id=PARTNER123",
        "paypal.connect.return-url=https://wallet.example/payments",
        "purchase.reconcile.enabled=false"
})
@EntityScan("xyz.tcheeric.payment.adapter.core.model.entity")
class PaypalEnabledContextTest {

    @Autowired
    ApplicationContext context;

    @Test
    void everyPaypalBeanWires() {
        assertThat(context.getBean(PaypalPurchaseCheckoutController.class)).isNotNull();
        assertThat(context.getBean(PaypalWebhookHandler.class)).isNotNull();
        assertThat(context.getBean(PaypalPurchaseReconciler.class)).isNotNull();
        assertThat(context.getBean(PaypalConnectService.class)).isNotNull();
        assertThat(context.getBean(PaypalConnectController.class)).isNotNull();
        assertThat(context.getBeansOfType(ProcessorLivePaymentCheck.class).values())
                .extracting(ProcessorLivePaymentCheck::processor).contains("paypal");
        assertThat(WebhookRegistry.getInstance().getHandler(PaypalWebhookHandler.PAYMENT_TYPE)).isPresent();
    }
}
