package xyz.tcheeric.payment.adapter.paypal.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/**
 * A PayPal subscription (Subscriptions API v1) as an instalment plan needs it: who it is, what state it is in, which
 * deployment and plan stamped it, and where the buyer approves it.
 *
 * @param id         PayPal's subscription id ({@code I-...})
 * @param status     {@code APPROVAL_PENDING}, {@code APPROVED}, {@code ACTIVE}, {@code SUSPENDED}, {@code CANCELLED}
 *                   or {@code EXPIRED}
 * @param customId   what this service sent as {@code custom_id}: {@code <environment>:<plan id>}
 * @param planId     PayPal's billing plan id ({@code P-...})
 * @param approveUrl the page the buyer approves the subscription on, or null once approved
 */
public record PaypalSubscription(String id, String status, String customId, String planId, String approveUrl) {

    public static final String APPROVAL_PENDING = "APPROVAL_PENDING";
    public static final String ACTIVE = "ACTIVE";

    /** Whether the buyer approved it and PayPal is (or was) billing it. */
    public boolean started() {
        return status != null && !awaitingApproval();
    }

    public boolean awaitingApproval() {
        return APPROVAL_PENDING.equalsIgnoreCase(status) || "APPROVED".equalsIgnoreCase(status);
    }

    /** Over for good: nothing can charge the buyer on it any more. */
    public boolean ended() {
        return "CANCELLED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status);
    }

    public static PaypalSubscription from(JsonNode subscription) {
        String approve = null;
        for (JsonNode link : subscription.path("links")) {
            if ("approve".equalsIgnoreCase(link.path("rel").asText())) {
                approve = PaypalOrder.text(link.path("href"));
            }
        }
        String status = PaypalOrder.text(subscription.path("status"));
        return new PaypalSubscription(
                PaypalOrder.text(subscription.path("id")),
                status == null ? null : status.toUpperCase(Locale.ROOT),
                PaypalOrder.text(subscription.path("custom_id")),
                PaypalOrder.text(subscription.path("plan_id")),
                approve);
    }
}
