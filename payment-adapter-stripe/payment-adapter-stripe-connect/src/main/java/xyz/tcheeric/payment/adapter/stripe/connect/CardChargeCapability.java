package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.model.Account;

/**
 * Whether a connected account can take A CARD.
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>Not the same question as Stripe's {@code charges_enabled}, and the
 * difference shipped a bug. Express grants {@code transfers} by default, and
 * transfers alone are enough to set that flag — so an account with
 * {@code card_payments: unrequested} reported:
 *
 * <pre>
 * charges_enabled: true   payouts_enabled: true   details_submitted: true
 * requirements.currently_due: []      disabled_reason: null
 * capabilities: {transfers: active}
 * </pre>
 *
 * <p>Nothing looked wrong anywhere. Every consumer of this field treats it as
 * "can sell today": the wallet's Payments screen renders it, the merchant
 * mirror republishes {@code acceptsCards} from it, and shoppers get a Buy
 * button. The truth surfaced only inside Stripe's hosted checkout, as a 400 at
 * confirm, in front of a buyer who had already typed their card.
 *
 * <p>IT LIVES HERE BECAUSE THERE ARE TWO SNAPSHOT BUILDERS. {@link
 * StripeSdkConnectClient} maps accounts fetched from the API; {@link
 * StripeConnectService} maps accounts arriving on the {@code account.updated}
 * webhook, and writes the result straight to the database. Fixing only the
 * first left the webhook free to overwrite a correct {@code false} with the old
 * lie the moment Stripe reported any account change — a fix that holds until
 * the exact event most likely to follow onboarding.
 *
 * <p>Two copies of a rule that must agree is what caused that, so there is one
 * copy and both callers use it.
 */
final class CardChargeCapability {

    private CardChargeCapability() {}

    /** Stripe's own status string for a capability that is granted and usable. */
    private static final String ACTIVE = "active";

    /**
     * True only when Stripe would accept a card charge on this account today.
     *
     * <p>A NARROWING of {@code charges_enabled}, not a replacement: the account
     * must still be chargeable AND hold the capability. The opposite mistake
     * would be worse — an account Stripe has stopped for its own reasons must
     * stay stopped even while {@code card_payments} still reads active.
     *
     * <p>{@code requested} and {@code pending} both answer false. That is the
     * state immediately after onboarding begins, and treating it as ready would
     * put the Buy button back a few minutes early through a different door.
     */
    static boolean canTakeCards(Account account) {
        if (account == null || !Boolean.TRUE.equals(account.getChargesEnabled())) {
            return false;
        }
        Account.Capabilities capabilities = account.getCapabilities();
        return capabilities != null && ACTIVE.equals(capabilities.getCardPayments());
    }
}
