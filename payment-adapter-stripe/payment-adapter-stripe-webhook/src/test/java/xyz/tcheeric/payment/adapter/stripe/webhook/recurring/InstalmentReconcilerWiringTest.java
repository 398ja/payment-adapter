package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripeReconcileCursorRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.InstalmentReconciler;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileConfiguration;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/**
 * The reconciler is built by the existing reconcile configuration. It must exist only with plans on, must find the SDK
 * source that can read instalments, and must not disturb the one-off reconciler beside it.
 */
class InstalmentReconcilerWiringTest {

    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(StripeGatewayProperties.class)
    static class GatewayProperties { }

    /**
     * A bare ApplicationContextRunner has no Duration conversion, which @Value("PT10M") needs and a real Boot app gets
     * from its conversion service. Without it even the existing one-off reconciler cannot be built here.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(GatewayProperties.class, StripeReconcileConfiguration.class, RecurringConfiguration.class)
            .withBean(ConnectedStripeAccountRepository.class, () -> mock(ConnectedStripeAccountRepository.class))
            .withBean(StripeReconcileCursorRepository.class, () -> mock(StripeReconcileCursorRepository.class))
            .withBean(StripePurchaseRepository.class, () -> mock(StripePurchaseRepository.class))
            .withBean(RecurringPlanRepository.class, () -> mock(RecurringPlanRepository.class))
            .withBean(StripePurchaseListener.class, () -> mock(StripePurchaseListener.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(MeterRegistry.class, () -> mock(MeterRegistry.class))
            .withPropertyValues("stripe.enabled=true", "stripe.secret-key=sk_test_x",
                    "stripe.success-url=https://w/ok", "stripe.cancel-url=https://w/no");

    // With plans off (the default) the instalment reconciler does not exist, and the one-off reconciler is untouched.
    @Test
    void notBuiltWhenPlansAreOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(InstalmentReconciler.class);
        });
    }

    // With plans on it is built, with the SDK source that reads instalments (a missing interface would fail startup).
    @Test
    void builtWhenPlansAreOn() {
        runner.withPropertyValues("stripe.recurring.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InstalmentReconciler.class);
        });
    }

    // Its schedule has defaults, so enabling plans needs no extra property to start reconciling.
    @Test
    void itsSettingsHaveSafeDefaults() {
        runner.withPropertyValues("stripe.recurring.enabled=true", "stripe.recurring.reconcile.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InstalmentReconciler.class);
        });
    }
}
