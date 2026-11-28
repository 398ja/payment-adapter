package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;

/**
 * The unit tests build every object by hand, so a wrong condition or a missing bean would only show at startup.
 * These boot a real Spring context: nothing about plans exists unless the property is on, and with it on every bean
 * and controller wires.
 */
class RecurringWiringTest {

    private static final String STRIPE_ON = "stripe.enabled=true";

    /** The real properties, bound from the same prefix production uses. */
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(StripeGatewayProperties.class)
    static class GatewayPropertiesConfiguration { }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // StripeGatewayProperties validates itself when stripe.enabled is on, so the real binding is used.
            .withUserConfiguration(GatewayPropertiesConfiguration.class)
            .withBean(RecurringPlanRepository.class, () -> mock(RecurringPlanRepository.class))
            .withBean(StripePurchaseRepository.class, () -> mock(StripePurchaseRepository.class))
            .withBean(ConnectedStripeAccountRepository.class, () -> mock(ConnectedStripeAccountRepository.class))
            .withBean(StripeCheckoutService.class, () -> mock(StripeCheckoutService.class))
            .withBean(StallReadinessClient.class, () -> mock(StallReadinessClient.class))
            .withUserConfiguration(RecurringConfiguration.class, PlanCheckoutController.class, PlanManagementController.class);

    // With the property off (the default), nothing about plans exists: no service, no client, no endpoint.
    @Test
    void nothingExistsUntilPlansAreEnabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RecurringPlanService.class);
            assertThat(context).doesNotHaveBean(StripeSubscriptionClient.class);
            assertThat(context).doesNotHaveBean(PlanCheckoutController.class);
            assertThat(context).doesNotHaveBean(PlanManagementController.class);
        });
    }

    // With stripe and plans both on, the service, the client, both endpoints and their properties all wire.
    @Test
    void everythingWiresWhenPlansAreEnabled() {
        runner.withPropertyValues(STRIPE_ON, "stripe.recurring.enabled=true", "stripe.secret-key=sk_test_x", "stripe.success-url=https://w/ok", "stripe.cancel-url=https://w/no").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RecurringPlanService.class);
            assertThat(context).hasSingleBean(StripeSubscriptionClient.class);
            assertThat(context).hasSingleBean(PlanCheckoutController.class);
            assertThat(context).hasSingleBean(PlanManagementController.class);
            assertThat(context.getBean(StripeRecurringProperties.class).getMinInstalmentMinor()).isEqualTo(2000L);
        });
    }

    // The endpoints need Stripe itself: plans on without stripe.enabled gives no endpoints rather than a failed start.
    @Test
    void plansWithoutStripeGiveNoEndpoints() {
        runner.withPropertyValues("stripe.recurring.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PlanCheckoutController.class);
            assertThat(context).doesNotHaveBean(PlanManagementController.class);
        });
    }

    // The limits are configurable, and requireControls defaults to the safe value.
    @Test
    void thePropertiesBindAndDefaultSafely() {
        runner.withPropertyValues(STRIPE_ON, "stripe.recurring.enabled=true", "stripe.secret-key=sk_test_x",
                "stripe.success-url=https://w/ok", "stripe.cancel-url=https://w/no",
                "stripe.recurring.min-instalment-minor=3000", "stripe.recurring.max-instalments=12").run(context -> {
            StripeRecurringProperties p = context.getBean(StripeRecurringProperties.class);
            assertThat(p.getMinInstalmentMinor()).isEqualTo(3000L);
            assertThat(p.getMaxInstalments()).isEqualTo(12);
            assertThat(p.isRequireControls()).isTrue();
        });
    }
}
