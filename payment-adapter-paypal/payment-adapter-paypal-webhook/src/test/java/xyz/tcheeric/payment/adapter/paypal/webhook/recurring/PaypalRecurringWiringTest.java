package xyz.tcheeric.payment.adapter.paypal.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionsClient;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalWebhookHandler;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.ProcessorPlanCanceller;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/** Regression: PayPal plans are wired only when switched on, and the webhook then reaches the plan service. */
class PaypalRecurringWiringTest {

    @Configuration
    @EnableConfigurationProperties(PaypalGatewayProperties.class)
    static class GatewayProperties { }

    private final PaypalWebhookHandler handler = mock(PaypalWebhookHandler.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(PaypalRecurringConfiguration.class)
            .withUserConfiguration(GatewayProperties.class)
            .withPropertyValues("paypal.client-id=id", "paypal.client-secret=secret",
                    "paypal.success-url=https://w/ok", "paypal.cancel-url=https://w/no")
            .withBean(PaypalWebhookHandler.class, () -> handler)
            .withBean(PaypalSubscriptionsClient.class, () -> mock(PaypalSubscriptionsClient.class))
            .withBean(RecurringPlanRepository.class, () -> mock(RecurringPlanRepository.class))
            .withBean(StripePurchaseRepository.class, () -> mock(StripePurchaseRepository.class))
            .withBean(StripePurchaseListener.class, () -> mock(StripePurchaseListener.class));

    // With plans off (the default) nothing about PayPal plans exists.
    @Test
    void notBuiltWhenPlansAreOff() {
        runner.withPropertyValues("paypal.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PaypalRecurringPlanService.class);
            assertThat(context).doesNotHaveBean(ProcessorPlanCanceller.class);
        });
    }

    // With plans on, the service, reconciler and canceller exist, and the webhook handler is given the service.
    @Test
    void builtAndHandedToTheWebhookWhenOn() {
        runner.withPropertyValues("paypal.enabled=true", "paypal.recurring.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PaypalInstalmentReconciler.class);
            assertThat(context.getBean(ProcessorPlanCanceller.class).processor()).isEqualTo("paypal");
            verify(handler).setRecurringPlans(context.getBean(PaypalRecurringPlanService.class));
        });
    }
}
