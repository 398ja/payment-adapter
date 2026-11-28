package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.model.Account;

/**
 * What Stripe says about who pays and who is liable on a connected account, read null-safely.
 *
 * <p>Imani offers instalment plans only where the stall pays Stripe's fees and Stripe bears negative balances,
 * so these values decide eligibility. An account the stall opened itself may not report a {@code controller}
 * block, so every answer can be null, and null means "unknown", never "fine".
 */
final class AccountControls {

    private AccountControls() {}

    static String type(Account account) {
        return account == null ? null : account.getType();
    }

    static String feePayer(Account account) {
        return account == null || account.getController() == null || account.getController().getFees() == null
                ? null
                : account.getController().getFees().getPayer();
    }

    static String lossesPayer(Account account) {
        return account == null || account.getController() == null || account.getController().getLosses() == null
                ? null
                : account.getController().getLosses().getPayments();
    }

    static String dashboardType(Account account) {
        return account == null || account.getController() == null || account.getController().getStripeDashboard() == null
                ? null
                : account.getController().getStripeDashboard().getType();
    }
}
