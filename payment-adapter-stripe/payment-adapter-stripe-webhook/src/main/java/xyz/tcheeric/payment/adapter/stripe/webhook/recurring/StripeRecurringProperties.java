package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code stripe.recurring.*}: instalment plans. Off until switched on. */
@Data
@ConfigurationProperties(prefix = "stripe.recurring")
public class StripeRecurringProperties {

    /** Turns plan creation and the invoice event handlers on. */
    private boolean enabled;

    /** The smallest instalment accepted, in minor units. Small instalments cost the stall more (spec, Appendix A). */
    private long minInstalmentMinor = 2000;

    /** The longest fixed term, in instalments. */
    private int maxInstalments = 24;

    /**
     * Refuse a plan on an account whose fee payer or loss collector Stripe has not reported. Unknown is not "fine":
     * the plan would run on an account where Imani might bear the Billing fee or a negative balance. Relax only
     * after confirming, for the accounts you connect, that Stripe reports them.
     */
    private boolean requireControls = true;
}
