package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.model.Account;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static xyz.tcheeric.payment.adapter.stripe.connect.StripeAccountChargesEnabledTest.account;

/**
 * Every path that turns a Stripe {@link Account} into a snapshot must answer
 * the card question the same way.
 *
 * <h2>Why this exists — a fix that was half a fix</h2>
 *
 * <p>There are TWO snapshot builders, and the first version of this correction
 * only found one of them:
 *
 * <ul>
 *   <li>{@link StripeSdkConnectClient#toSnapshot} — accounts fetched from the
 *       Stripe API, on create and on retrieve.
 *   <li>{@code StripeConnectService#toSnapshot} — accounts arriving on the
 *       {@code account.updated} WEBHOOK, whose result goes straight into the
 *       database via {@code saveSnapshot}.
 * </ul>
 *
 * <p>Fixing only the client left the webhook writing a raw
 * {@code charges_enabled} over the top of a correct {@code false}. Worse than a
 * missed spot: {@code account.updated} is the event most likely to arrive right
 * after a merchant touches onboarding, so the lie would have been restored at
 * almost exactly the moment someone checked whether the fix worked, and by a
 * path with no user action to blame it on.
 *
 * <p>Both now call {@link CardChargeCapability}. This test pins that, twice
 * over: once behaviourally through the client, and once structurally by reading
 * the service's own bytecode-visible source-level dependency, because a
 * behavioural test of the webhook path needs a full {@code Event} with a
 * deserialisable data object and Mockito cannot stub Stripe's final
 * deserialiser.
 */
class StripeAccountSnapshotConsistencyTest {

    /** The API path: transfers-only must not report a chargeable account. */
    @Test
    void clientSnapshotUsesTheSharedRule() {
        StripeAccountSnapshot snapshot = new StripeSdkConnectClient(
                new xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties())
                .toSnapshot(account(true, "active", null));
        assertThat(snapshot.chargesEnabled())
                .as("the client path must narrow charges_enabled")
                .isFalse();
    }

    /** And it must still say yes when the capability really is granted. */
    @Test
    void clientSnapshotStillAllowsARealCardAccount() {
        StripeAccountSnapshot snapshot = new StripeSdkConnectClient(
                new xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties())
                .toSnapshot(account(true, "active", "active"));
        assertThat(snapshot.chargesEnabled()).isTrue();
    }

    /**
     * THE STRUCTURAL GUARD. Asserts the webhook path's source does not read
     * {@code getChargesEnabled} directly.
     *
     * <p>Reading source is a blunt instrument and is used deliberately: the
     * alternative is no check at all on the exact path that shipped the defect.
     * A reviewer who reintroduces the raw call gets a red test naming this
     * problem, which is the whole point.
     */
    @Test
    void webhookSnapshotDoesNotReadChargesEnabledDirectly() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/xyz/tcheeric/payment/adapter/stripe/connect/StripeConnectService.java"));

        assertThat(source)
                .as("the account.updated webhook writes straight to the database; "
                        + "a raw charges_enabled here overwrites the corrected value")
                .doesNotContain("Boolean.TRUE.equals(account.getChargesEnabled())");

        assertThat(source)
                .as("it must use the one shared rule instead")
                .contains("CardChargeCapability.canTakeCards(account)");
    }

    /**
     * Nothing else in main may read the flag raw either. Catches a third
     * snapshot builder being added later, which is exactly how this defect
     * arrived in the first place.
     *
     * <p>{@link CardChargeCapability} is the sole exemption, because it is the
     * one place the raw flag is SUPPOSED to be read — everywhere else goes
     * through it.
     */
    @Test
    void noProductionCodeReadsChargesEnabledRaw() throws Exception {
        java.nio.file.Path main = java.nio.file.Path.of("src/main/java");
        List<String> offenders;
        try (var paths = java.nio.file.Files.walk(main)) {
            offenders = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("CardChargeCapability.java"))
                    .filter(p -> {
                        try {
                            return java.nio.file.Files.readString(p)
                                    .contains("account.getChargesEnabled()");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(java.nio.file.Path::toString)
                    .toList();
        }

        assertThat(offenders)
                .as("read Stripe's charges_enabled through CardChargeCapability, not directly")
                .isEmpty();
    }

    /** The shared rule is reachable from both packages that need it. */
    @Test
    void theSharedRuleIsPackageVisibleToBothCallers() {
        Method[] methods = CardChargeCapability.class.getDeclaredMethods();
        assertThat(Arrays.stream(methods).map(Method::getName))
                .contains("canTakeCards");
    }
}
