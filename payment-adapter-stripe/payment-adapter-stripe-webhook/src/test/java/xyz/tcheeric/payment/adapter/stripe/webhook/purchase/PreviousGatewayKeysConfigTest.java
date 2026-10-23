package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rollover variables reach {@link PreviousGatewayKeys} from the environment names the compose
 * files and the runbook use (payment-adapter#288, second check of #303): in particular the
 * {@code _FROM} pin, without which a restart inside the window widens the old key's allowance.
 */
class PreviousGatewayKeysConfigTest {

    private static final String OLD = PreviousGatewayKeysTest.pubkeyOf("1".repeat(64));

    /** Only the bean under test: the rest of PurchaseConfiguration needs a database. */
    @Configuration
    static class Keys {
        @Bean
        PurchaseConfiguration purchaseConfiguration() {
            return new PurchaseConfiguration();
        }

        @Bean
        PreviousGatewayKeys previousGatewayKeys(PurchaseConfiguration config) {
            return config.previousGatewayKeys();
        }
    }

    private static ApplicationContextRunner withEnv(Map<String, Object> env) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env)))
                .withUserConfiguration(Keys.class);
    }

    @Test
    void thePinnedFromIsReadFromItsEnvironmentName() {
        Instant firstBoot = Instant.now().minus(Duration.ofDays(2));
        withEnv(Map.of(
                "PURCHASE_GATEWAY_PREVIOUS_PUBKEYS", OLD,
                "PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_UNTIL", Instant.now().plus(Duration.ofDays(7)).toString(),
                "PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_FROM", firstBoot.toString()))
                .run(ctx -> {
                    PreviousGatewayKeys keys = ctx.getBean(PreviousGatewayKeys.class);
                    assertEquals(firstBoot, keys.adoptedAt());
                    assertTrue(keys.recognisesFor(OLD, firstBoot));
                    assertFalse(keys.recognisesFor(OLD, firstBoot.plusSeconds(1)));
                });
    }

    @Test
    void withoutAPinTheCutoffIsThisBoot() {
        Instant before = Instant.now();
        withEnv(Map.of(
                "PURCHASE_GATEWAY_PREVIOUS_PUBKEYS", OLD,
                "PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_UNTIL", Instant.now().plus(Duration.ofDays(7)).toString()))
                .run(ctx -> {
                    PreviousGatewayKeys keys = ctx.getBean(PreviousGatewayKeys.class);
                    assertFalse(keys.adoptedAt().isBefore(before));
                    assertTrue(keys.inForce());
                });
    }

    @Test
    void aGarbledPinStopsTheBoot() {
        withEnv(Map.of(
                "PURCHASE_GATEWAY_PREVIOUS_PUBKEYS", OLD,
                "PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_UNTIL", Instant.now().plus(Duration.ofDays(7)).toString(),
                "PURCHASE_GATEWAY_PREVIOUS_PUBKEYS_FROM", "last tuesday"))
                .run(ctx -> assertTrue(ctx.getStartupFailure() != null, "the context must not start"));
    }
}
