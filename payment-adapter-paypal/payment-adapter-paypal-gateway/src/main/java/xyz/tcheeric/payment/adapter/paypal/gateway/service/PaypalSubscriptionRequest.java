package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import java.util.Locale;
import java.util.Set;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalValidationException;

/**
 * What it takes to start one instalment plan on a seller's PayPal account.
 *
 * @param planId       Imani's plan id; seeds every {@code PayPal-Request-Id}
 * @param merchantId   the seller (stall) the product, plan and subscription are created for
 * @param amountMinor  one instalment
 * @param currency     lower or upper case ISO code
 * @param interval     {@code week}, {@code month} or {@code year}
 * @param instalments  how many instalments, or null for open-ended (PayPal: {@code total_cycles=0})
 * @param customId     {@code <environment>:<plan id>}, carried on the subscription
 * @param description  shown to the buyer on PayPal's page
 * @param brandName    shown to the buyer, or null
 * @param returnUrl    where PayPal sends the buyer after approving
 * @param cancelUrl    where PayPal sends the buyer who gives up
 */
public record PaypalSubscriptionRequest(String planId, String merchantId, long amountMinor, String currency,
        String interval, Integer instalments, String customId, String description, String brandName,
        String returnUrl, String cancelUrl) {

    private static final Set<String> INTERVALS = Set.of("week", "month", "year");

    public PaypalSubscriptionRequest {
        if (planId == null || planId.isBlank()) {
            throw new PaypalValidationException("a PayPal subscription needs Imani's plan id");
        }
        if (merchantId == null || merchantId.isBlank()) {
            throw new PaypalValidationException("a PayPal subscription must be created for a seller");
        }
        if (amountMinor <= 0) {
            throw new PaypalValidationException("an instalment must be more than zero");
        }
        if (interval == null || !INTERVALS.contains(interval.toLowerCase(Locale.ROOT))) {
            throw new PaypalValidationException("interval must be week, month or year");
        }
        if (instalments != null && instalments < 1) {
            throw new PaypalValidationException("instalments must be at least one, or left out");
        }
    }

    /** PayPal's {@code interval_unit}. */
    public String intervalUnit() {
        return interval.toUpperCase(Locale.ROOT);
    }

    /** PayPal's {@code total_cycles}: 0 means "until cancelled". */
    public int totalCycles() {
        return instalments == null ? 0 : instalments;
    }
}
