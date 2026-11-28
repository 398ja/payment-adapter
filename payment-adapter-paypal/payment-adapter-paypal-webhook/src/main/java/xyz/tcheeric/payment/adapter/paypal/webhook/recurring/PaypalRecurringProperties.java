package xyz.tcheeric.payment.adapter.paypal.webhook.recurring;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code paypal.recurring.*}: PayPal instalment plans (ADR 0003). Off until switched on. */
@Data
@ConfigurationProperties(prefix = "paypal.recurring")
public class PaypalRecurringProperties {

    /** Turns plan creation, the subscription event handlers and the instalment reconciler on. */
    private boolean enabled;

    /** The smallest instalment accepted, in minor units, as {@code stripe.recurring.min-instalment-minor}. */
    private long minInstalmentMinor = 2000;

    /** The longest fixed term for a monthly plan. */
    private int maxInstalments = 24;

    /** The longest fixed term for a weekly plan. */
    private int maxInstalmentsWeekly = 52;

    /** The longest fixed term for a yearly plan. */
    private int maxInstalmentsYearly = 5;

    /** Shown to the buyer on PayPal's approval page, as the plan's product name. */
    private String description = "Instalment plan";

    /** How often the instalment reconciler runs. */
    private Duration reconcileInterval = Duration.ofMinutes(10);

    /** A sale younger than this is left to its webhook. */
    private Duration reconcileGrace = Duration.ofMinutes(10);

    /** How long a plan is left before it is read again. */
    private Duration reconcileRecheck = Duration.ofHours(1);

    /** How far back a plan's transactions are read, and how long an ended plan is still checked for a late sale. */
    private Duration reconcileLookback = Duration.ofDays(35);

    /** A plan the buyer never approved is closed (and its subscription cancelled) after this long. */
    private Duration abandonAfter = Duration.ofHours(6);

    /** Plans read per run. */
    private int reconcileBatchSize = 50;

    public int maxInstalmentsFor(String interval) {
        return switch (interval) {
            case "week" -> maxInstalmentsWeekly;
            case "year" -> maxInstalmentsYearly;
            default -> maxInstalments;
        };
    }
}
