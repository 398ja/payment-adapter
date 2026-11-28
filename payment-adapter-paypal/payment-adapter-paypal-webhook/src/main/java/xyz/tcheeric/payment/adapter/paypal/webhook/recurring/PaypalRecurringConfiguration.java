package xyz.tcheeric.payment.adapter.paypal.webhook.recurring;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionsClient;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalWebhookHandler;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.ProcessorPlanCanceller;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/** PayPal instalment plans (ADR 0003). Nothing here exists unless {@code paypal.enabled} and {@code paypal.recurring.enabled}. */
@Configuration
@ConditionalOnProperty(prefix = "paypal", name = {"enabled", "recurring.enabled"}, havingValue = "true")
@EnableConfigurationProperties(PaypalRecurringProperties.class)
public class PaypalRecurringConfiguration {

    @Bean
    PaypalRecurringPlanService paypalRecurringPlanService(RecurringPlanRepository plans,
            StripePurchaseRepository purchases, StripePurchaseListener listener, PaypalGatewayProperties properties,
            PaypalWebhookHandler handler) {
        PaypalRecurringPlanService service = new PaypalRecurringPlanService(plans, purchases, listener,
                properties.resolvedEnvironment(), properties.isLive());
        handler.setRecurringPlans(service);
        return service;
    }

    @Bean
    PaypalInstalmentReconciler paypalInstalmentReconciler(RecurringPlanRepository plans,
            StripePurchaseRepository purchases, PaypalSubscriptionsClient paypal, PaypalRecurringPlanService service,
            PaypalRecurringProperties properties) {
        return new PaypalInstalmentReconciler(plans, purchases, paypal, service, properties, Clock.systemUTC());
    }

    /** The buyer's cancel from the shared plan routes ({@code /api/v1/checkout/plans/{id}/cancel}). */
    @Bean
    ProcessorPlanCanceller paypalPlanCanceller(PaypalSubscriptionsClient paypal) {
        return new ProcessorPlanCanceller() {
            @Override
            public String processor() {
                return RecurringPlan.PROCESSOR_PAYPAL;
            }

            @Override
            public void cancel(RecurringPlan plan) {
                if (plan.getSubscriptionId() != null) {
                    paypal.cancelSubscription(plan.getConnectedAccountId(), plan.getSubscriptionId(),
                            "Cancelled by the buyer");
                }
            }
        };
    }
}
