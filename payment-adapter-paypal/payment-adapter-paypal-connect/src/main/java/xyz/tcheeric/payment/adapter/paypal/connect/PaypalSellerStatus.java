package xyz.tcheeric.payment.adapter.paypal.connect;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * What PayPal says about a seller ({@code GET /v1/customer/partners/{partner}/merchant-integrations/{merchant}}),
 * the counterpart of {@code StripeAccountSnapshot}'s raw source.
 *
 * @param merchantId           the seller's PayPal merchant id
 * @param trackingId           what this service sent at referral: ties the seller to one stall
 * @param paymentsReceivable   PayPal has not restricted the account from receiving money
 * @param primaryEmailConfirmed the seller confirmed their email; until then PayPal holds payments
 * @param grantedScopes        the OAuth scopes the seller granted THIS platform's app
 * @param primaryEmail         the seller's login email, or null
 * @param country              the seller's country, or null when PayPal does not say
 */
public record PaypalSellerStatus(
        String merchantId,
        String trackingId,
        boolean paymentsReceivable,
        boolean primaryEmailConfirmed,
        Set<String> grantedScopes,
        String primaryEmail,
        String country) {

    /** The scope PayPal grants for the {@code PAYMENT} feature: create and capture orders for the seller. */
    public static final String PAYMENT_SCOPE = "https://uri.paypal.com/services/payments/realtimepayment";

    /** The {@code REFUND} feature's scope; not required to sell, recorded for the operator. */
    public static final String REFUND_SCOPE = "https://uri.paypal.com/services/payments/refund";

    public boolean grants(String scope) {
        return grantedScopes.contains(scope);
    }

    /**
     * @param partnerClientId this platform's REST client id: scopes granted to another partner's app
     *                        say nothing about what this one may do
     */
    public static PaypalSellerStatus from(JsonNode json, String partnerClientId) {
        Set<String> scopes = new TreeSet<>();
        for (JsonNode integration : json.path("oauth_integrations")) {
            for (JsonNode party : integration.path("oauth_third_party")) {
                String partner = party.path("partner_client_id").asText(null);
                if (partner != null && partnerClientId != null && !partner.equals(partnerClientId)) {
                    continue;
                }
                for (JsonNode scope : party.path("scopes")) {
                    scopes.add(scope.asText());
                }
            }
        }
        String country = text(json.path("country"));
        return new PaypalSellerStatus(
                text(json.path("merchant_id")),
                text(json.path("tracking_id")),
                json.path("payments_receivable").asBoolean(false),
                json.path("primary_email_confirmed").asBoolean(false),
                Set.copyOf(scopes),
                text(json.path("primary_email")),
                country == null ? null : country.toUpperCase(Locale.ROOT));
    }

    /** What the seller still has to do, named for the Payments screen as Stripe's {@code currently_due}. */
    public List<String> requirementsDue() {
        List<String> due = new ArrayList<>();
        if (!primaryEmailConfirmed) {
            due.add("primary_email_confirmation");
        }
        if (!grants(PAYMENT_SCOPE)) {
            due.add("payment_permission");
        }
        return List.copyOf(due);
    }

    /** Why PayPal will not pay this seller, or null. */
    public String disabledReason() {
        return paymentsReceivable ? null : "payments_not_receivable";
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }
}
