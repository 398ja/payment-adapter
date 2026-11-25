package xyz.tcheeric.payment.adapter.paypal.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One capture: the moment the buyer's money moved to the seller, and the processor reference a
 * coupon carries (ADR 0002).
 *
 * @param id              the capture id
 * @param status          {@code COMPLETED}, {@code PENDING}, {@code DECLINED}, {@code PARTIALLY_REFUNDED},
 *                        {@code REFUNDED}, {@code FAILED}
 * @param amountMinor     in minor units
 * @param currency        lower case
 * @param customId        the order's {@code custom_id}, which a capture repeats
 * @param orderId         the order it captured
 * @param payeeMerchantId the seller paid, where PayPal says
 */
public record PaypalCapture(String id, String status, Long amountMinor, String currency, String customId,
                            String orderId, String payeeMerchantId) {

    /** The money is the seller's: the only state a purchase is recorded owed in. */
    public boolean completed() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

    /** Settled at some point, whatever has been refunded since. */
    public boolean succeeded() {
        return completed() || "PARTIALLY_REFUNDED".equalsIgnoreCase(status) || "REFUNDED".equalsIgnoreCase(status);
    }

    public boolean pending() {
        return "PENDING".equalsIgnoreCase(status);
    }

    public boolean failed() {
        return "DECLINED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

    public boolean fullyRefunded() {
        return "REFUNDED".equalsIgnoreCase(status);
    }

    /**
     * @param capture      a capture object, from an order, a capture read or a webhook resource
     * @param knownOrderId the order it belongs to when the caller already knows it
     */
    public static PaypalCapture from(JsonNode capture, String knownOrderId) {
        String currency = PaypalOrder.text(capture.path("amount").path("currency_code"));
        Long amount = null;
        if (currency != null && capture.path("amount").hasNonNull("value")) {
            amount = PaypalMoney.toMinor(capture.path("amount").path("value").asText(), currency);
        }
        String orderId = PaypalOrder.text(capture.path("supplementary_data").path("related_ids").path("order_id"));
        return new PaypalCapture(
                capture.path("id").asText(null),
                capture.path("status").asText(null),
                amount,
                currency == null ? null : currency.toLowerCase(java.util.Locale.ROOT),
                PaypalOrder.text(capture.path("custom_id")),
                orderId != null ? orderId : knownOrderId,
                PaypalOrder.text(capture.path("payee").path("merchant_id")));
    }
}
