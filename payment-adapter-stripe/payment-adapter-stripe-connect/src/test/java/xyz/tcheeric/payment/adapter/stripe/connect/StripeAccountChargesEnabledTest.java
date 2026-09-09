package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.model.Account;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code chargesEnabled} is allowed to mean by the time it leaves here.
 *
 * <h2>The lie this closes</h2>
 *
 * <p>Snapshots used to copy Stripe's {@code charges_enabled} through verbatim.
 * Express accounts are granted {@code transfers} by default, and transfers
 * ALONE set that flag — so a real connected account looked like this:
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
 *
 * @see StripeAccountSnapshotConsistencyTest for the proof that BOTH snapshot
 *     builders use this rule — the first version of this fix corrected only one
 */
class StripeAccountChargesEnabledTest {

    /** THE BUG, exactly as observed live on acct_1UDZ7BQ0Rz45vDTA. */
    @Test
    void transfersOnlyAccountCannotTakeCards() {
        assertThat(CardChargeCapability.canTakeCards(account(true, "active", null)))
                .as("transfers alone set Stripe's flag; it cannot pay for a coupon")
                .isFalse();
    }

    /** The account we actually want: chargeable, and card_payments granted. */
    @Test
    void activeCardPaymentsCanTakeCards() {
        assertThat(CardChargeCapability.canTakeCards(account(true, "active", "active"))).isTrue();
    }

    /**
     * Requested-but-not-yet-granted is still a no. This is the state right after
     * onboarding starts, and treating it as ready would recreate the same
     * premature Buy button through a different door.
     */
    @Test
    void pendingCardPaymentsIsNotYetReady() {
        assertThat(CardChargeCapability.canTakeCards(account(true, "active", "pending"))).isFalse();
        assertThat(CardChargeCapability.canTakeCards(account(true, "active", "inactive"))).isFalse();
    }

    /**
     * Stripe's own flag is still respected. The check NARROWS it rather than
     * replacing it, so an account Stripe has stopped for its own reasons stays
     * stopped even while the capability reads active.
     */
    @Test
    void stripeSayingNoStillWins() {
        assertThat(CardChargeCapability.canTakeCards(account(false, "active", "active"))).isFalse();
    }

    /** No capabilities object at all must not throw, and must not be ready. */
    @Test
    void missingCapabilitiesIsNotReady() {
        Account account = new Account();
        account.setChargesEnabled(true);
        assertThat(CardChargeCapability.canTakeCards(account)).isFalse();
    }

    /** Nor may a null account, which a malformed webhook payload could produce. */
    @Test
    void nullAccountIsNotReady() {
        assertThat(CardChargeCapability.canTakeCards(null)).isFalse();
    }

    static Account account(boolean chargesEnabled, String transfers, String cardPayments) {
        Account account = new Account();
        account.setChargesEnabled(chargesEnabled);
        account.setPayoutsEnabled(true);
        account.setDetailsSubmitted(true);
        Account.Capabilities capabilities = new Account.Capabilities();
        capabilities.setTransfers(transfers);
        capabilities.setCardPayments(cardPayments);
        account.setCapabilities(capabilities);
        return account;
    }
}
