package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

/** Instalment plans. Nothing here exists unless {@code stripe.recurring.enabled=true}. */
@Configuration
@ConditionalOnProperty(prefix = "stripe.recurring", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StripeRecurringProperties.class)
public class RecurringConfiguration {

    @Bean
    StripeSubscriptionClient stripeSubscriptionClient(StripeGatewayProperties properties) {
        return new StripeSdkSubscriptionClient(properties);
    }

    @Bean
    RecurringPlanService recurringPlanService(RecurringPlanRepository plans, StripePurchaseRepository purchases,
            StripeSubscriptionClient subscriptions, StripeGatewayProperties properties) {
        return new RecurringPlanService(plans, purchases, subscriptions, properties.resolvedEnvironment());
    }
}
