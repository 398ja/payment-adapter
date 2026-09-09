package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.model.Account;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code chargesEnabled} is allowed to mean by the time it leaves here.
 *
 * <h2>The lie this closes</h2>
 *
 * <p>The snapshot used to copy Stripe's {@code charges_enabled} through
 * verbatim. Express accounts are granted {@code transfers} by default, and
 * transfers ALONE set that flag — so a real connected account looked like this:
 *
 * <pre>
 * charges_enabled: true   payouts_enabled: true   details_submitted: true
 * requirements.currently_due: []      disabled_reason: null
 * capabilities: {transfers: active}   card_payments: unrequested
 * </pre>
 *
 * <p>Every field a human or a screen would check said "ready". The account
 * could not take a card. Downstream, {@code chargesEnabled} is the one flag the
 * wallet's Payments screen renders and the merchant mirror republishes
 * {@code acceptsCards} from, so this single boolean put a Buy button in front of
 * every shopper of a stall that could not sell. The failure appeared only inside
 * Stripe's hosted checkout — a 400 on {@code /v1/payment_pages/.../confirm} —
 * after a buyer had typed a real card number, with nothing logged on our side
 * because our side was never called.
 *
 * <p>Requesting the capability at creation ({@link StripeAccountCapabilitiesTest})
 * fixes new accounts. This fixes what we SAY about the accounts that already
 * exist without it, which no amount of correct creation code can reach.
 */
class StripeAccountChargesEnabledTest {

    /** THE BUG, exactly as observed live on acct_1UDZ7BQ0Rz45vDTA. */
    @Test
    void transfersOnlyAccountCannotTakeCards() {
        assertThat(snapshot(true, "active", null).chargesEnabled())
                .as("transfers alone set Stripe's flag; it cannot pay for a coupon")
                .isFalse();
    }

    /** The account we actually want: chargeable, and card_payments granted. */
    @Test
    void activeCardPaymentsCanTakeCards() {
        assertThat(snapshot(true, "active", "active").chargesEnabled()).isTrue();
    }

    /**
     * Requested-but-not-yet-granted is still a no. This is the state right after
     * onboarding starts, and treating it as ready would recreate the same
     * premature Buy button through a different door.
     */
    @Test
    void pendingCardPaymentsIsNotYetReady() {
        assertThat(snapshot(true, "active", "pending").chargesEnabled()).isFalse();
        assertThat(snapshot(true, "active", "inactive").chargesEnabled()).isFalse();
    }

    /**
     * Stripe's own flag is still respected. The check NARROWS it rather than
     * replacing it, so an account Stripe has stopped for its own reasons stays
     * stopped even while the capability reads active.
     */
    @Test
    void stripeSayingNoStillWins() {
        assertThat(snapshot(false, "active", "active").chargesEnabled()).isFalse();
    }

    /** No capabilities object at all must not throw, and must not be ready. */
    @Test
    void missingCapabilitiesIsNotReady() {
        Account account = new Account();
        account.setChargesEnabled(true);
        assertThat(client().toSnapshot(account).chargesEnabled()).isFalse();
    }

    /**
     * The other fields keep reporting Stripe verbatim. Only the card question is
     * narrowed, because {@code payoutsEnabled} and {@code detailsSubmitted} are
     * used to explain onboarding progress and would become misleading in the
     * opposite direction if they were forced false.
     */
    @Test
    void otherFieldsAreUntouched() {
        StripeAccountSnapshot snapshot = snapshot(true, "active", null);
        assertThat(snapshot.payoutsEnabled()).isTrue();
        assertThat(snapshot.detailsSubmitted()).isTrue();
    }

    private StripeAccountSnapshot snapshot(boolean chargesEnabled, String transfers, String cardPayments) {
        Account account = new Account();
        account.setChargesEnabled(chargesEnabled);
        account.setPayoutsEnabled(true);
        account.setDetailsSubmitted(true);
        Account.Capabilities capabilities = new Account.Capabilities();
        capabilities.setTransfers(transfers);
        capabilities.setCardPayments(cardPayments);
        account.setCapabilities(capabilities);
        return client().toSnapshot(account);
    }

    private StripeSdkConnectClient client() {
        return new StripeSdkConnectClient(new StripeGatewayProperties());
    }
}
