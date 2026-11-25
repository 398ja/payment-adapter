package xyz.tcheeric.payment.adapter.paypal.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * What this service needs to know about an Orders v2 order, read from PayPal's JSON.
 *
 * @param id              PayPal's order id
 * @param status          {@code CREATED}, {@code PAYER_ACTION_REQUIRED}, {@code APPROVED},
 *                        {@code COMPLETED}, {@code VOIDED}
 * @param approveUrl      where the buyer goes to pay ({@code payer-action}, or {@code approve})
 * @param customId        the first purchase unit's {@code custom_id}
 * @param payeeMerchantId the seller the order pays
 * @param amountMinor     the purchase unit's amount, in minor units, or null if absent
 * @param currency        lower case, as the ledger spells it
 * @param captures        the purchase unit's captures, newest last
 * @param refundedMinor   the sum of the purchase unit's COMPLETED refunds
 */
public record PaypalOrder(String id, String status, String approveUrl, String customId, String payeeMerchantId,
                          Long amountMinor, String currency, List<PaypalCapture> captures, long refundedMinor) {

    public boolean approved() {
        return "APPROVED".equalsIgnoreCase(status);
    }

    public boolean completed() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

    public boolean voided() {
        return "VOIDED".equalsIgnoreCase(status);
    }

    /** The capture that moved the money, or null: the last one, PayPal captures an order once. */
    public PaypalCapture capture() {
        return captures.isEmpty() ? null : captures.get(captures.size() - 1);
    }

    public static PaypalOrder from(JsonNode order) {
        JsonNode unit = order.path("purchase_units").path(0);
        String currency = text(unit.path("amount").path("currency_code"));
        Long amount = null;
        if (currency != null && unit.path("amount").hasNonNull("value")) {
            amount = PaypalMoney.toMinor(unit.path("amount").path("value").asText(), currency);
        }
        List<PaypalCapture> captures = new ArrayList<>();
        for (JsonNode capture : unit.path("payments").path("captures")) {
            captures.add(PaypalCapture.from(capture, order.path("id").asText(null)));
        }
        long refunded = 0L;
        for (JsonNode refund : unit.path("payments").path("refunds")) {
            if ("COMPLETED".equalsIgnoreCase(refund.path("status").asText())) {
                String code = text(refund.path("amount").path("currency_code"));
                refunded += PaypalMoney.toMinor(refund.path("amount").path("value").asText(),
                        code == null ? currency : code);
            }
        }
        return new PaypalOrder(
                order.path("id").asText(null),
                order.path("status").asText(null),
                approveLink(order),
                text(unit.path("custom_id")),
                text(unit.path("payee").path("merchant_id")),
                amount,
                currency == null ? null : currency.toLowerCase(java.util.Locale.ROOT),
                List.copyOf(captures),
                refunded);
    }

    private static String approveLink(JsonNode order) {
        String approve = null;
        for (JsonNode link : order.path("links")) {
            String rel = link.path("rel").asText("");
            if ("payer-action".equalsIgnoreCase(rel)) {
                return link.path("href").asText(null);
            }
            if ("approve".equalsIgnoreCase(rel)) {
                approve = link.path("href").asText(null);
            }
        }
        return approve;
    }

    static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }
}
