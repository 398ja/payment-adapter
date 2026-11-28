package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Plans run only where the stall pays Stripe's fees and Stripe bears a negative balance (spec R4). */
class RecurringEligibilityTest {

    private static ConnectedStripeAccount account(String fees, String losses) {
        ConnectedStripeAccount a = new ConnectedStripeAccount();
        a.setFeePayer(fees);
        a.setLossesPayer(losses);
        return a;
    }

    // A Standard-equivalent account (the stall pays fees, Stripe bears losses) may run plans.
    @Test
    void standardEquivalentAccountsAreEligible() {
        assertThat(RecurringEligibility.refusal(account("account", "stripe"), true)).isEmpty();
    }

    // A legacy Express account would make Imani pay the Billing fee and carry losses, so it is refused.
    @Test
    void legacyExpressIsRefused() {
        assertThat(RecurringEligibility.refusal(account("application_express", "application"), true)).isPresent();
        assertThat(RecurringEligibility.refusal(account("application_express", "application"), false)).isPresent();
    }

    // Imani paying the fees, or bearing losses, is refused on its own, whichever way round.
    @Test
    void eitherResponsibilityOnImaniRefuses() {
        assertThat(RecurringEligibility.refusal(account("application", "stripe"), false)).isPresent();
        assertThat(RecurringEligibility.refusal(account("account", "application"), false)).isPresent();
    }

    // Unknown is not "fine": with requireControls on, an account Stripe did not report is refused.
    @Test
    void anUnreportedAccountIsRefusedWhileControlsAreRequired() {
        assertThat(RecurringEligibility.refusal(account(null, null), true)).isPresent();
        assertThat(RecurringEligibility.refusal(account("account", null), true)).isPresent();
    }

    // An operator who has confirmed Stripe reports nothing for the accounts they connect can relax the rule.
    @Test
    void anUnreportedAccountIsAllowedOnlyWhenTheOperatorRelaxesTheRule() {
        assertThat(RecurringEligibility.refusal(account(null, null), false)).isEmpty();
    }

    // The status screen's flag is always strict: unreported is not eligible, and a missing account never is.
    @Test
    void theStallsEligibilityFlagIsStrictAndNeedsAnAccount() {
        ConnectedStripeAccount ok = account("account", "stripe");
        ok.setStripeAccountId("acct_1");
        assertThat(RecurringEligibility.eligible(ok)).isTrue();

        ConnectedStripeAccount unreported = account(null, null);
        unreported.setStripeAccountId("acct_2");
        assertThat(RecurringEligibility.eligible(unreported)).isFalse();

        assertThat(RecurringEligibility.eligible(account("account", "stripe"))).isFalse();
        assertThat(RecurringEligibility.eligible(null)).isFalse();
    }

    // Relaxing the rule never allows an account that DID report Imani as the payer.
    @Test
    void relaxingTheRuleNeverOverridesAReportedPayer() {
        assertThat(RecurringEligibility.refusal(account("application_custom", null), false)).isPresent();
    }
}
