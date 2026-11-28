package xyz.tcheeric.payment.adapter.paypal.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * One paid (or refunded) instalment of a PayPal subscription: a v1 "sale". PayPal reports it in two shapes, the
 * {@code PAYMENT.SALE.*} webhook resource and an entry of {@code GET /v1/billing/subscriptions/{id}/transactions}; both
 * are read into this one record so the webhook and the reconciler record an instalment through the same code.
 *
 * @param id             the sale id, which is the processor reference on the voucher's ledger row
 * @param state          lower case: {@code completed}, {@code pending}, {@code partially_refunded}, {@code refunded},
 *                       {@code reversed}, {@code denied}
 * @param amountMinor    what the buyer paid, in minor units, or null when PayPal did not say
 * @param currency       lower case
 * @param subscriptionId the subscription it pays ({@code billing_agreement_id}), or null when not known from the shape
 * @param paidAt         when PayPal took it, or null
 */
public record PaypalSale(String id, String state, Long amountMinor, String currency, String subscriptionId,
        Instant paidAt) {

    /** The money is the seller's and nothing was given back: the only state an instalment is recorded owed in. */
    public boolean completed() {
        return "completed".equals(state);
    }

    /** Settled at some point, whatever was refunded since. */
    public boolean succeeded() {
        return completed() || "partially_refunded".equals(state) || "refunded".equals(state);
    }

    public boolean fullyReturned() {
        return "refunded".equals(state) || "reversed".equals(state);
    }

    /** A {@code PAYMENT.SALE.*} webhook resource, or a {@code GET /v1/payments/sale/{id}} answer. */
    public static PaypalSale fromSale(JsonNode sale) {
        String currency = PaypalOrder.text(sale.path("amount").path("currency"));
        String total = PaypalOrder.text(sale.path("amount").path("total"));
        String state = PaypalOrder.text(sale.path("state"));
        return new PaypalSale(
                PaypalOrder.text(sale.path("id")),
                state == null ? null : state.toLowerCase(Locale.ROOT),
                currency == null || total == null ? null : PaypalMoney.toMinor(total, currency),
                currency == null ? null : currency.toLowerCase(Locale.ROOT),
                PaypalOrder.text(sale.path("billing_agreement_id")),
                instant(PaypalOrder.text(sale.path("create_time"))));
    }

    /** An entry of a subscription's transaction list. */
    public static PaypalSale fromTransaction(JsonNode transaction, String subscriptionId) {
        JsonNode gross = transaction.path("amount_with_breakdown").path("gross_amount");
        String currency = PaypalOrder.text(gross.path("currency_code"));
        String value = PaypalOrder.text(gross.path("value"));
        String status = PaypalOrder.text(transaction.path("status"));
        return new PaypalSale(
                PaypalOrder.text(transaction.path("id")),
                status == null ? null : status.toLowerCase(Locale.ROOT),
                currency == null || value == null ? null : PaypalMoney.toMinor(value, currency),
                currency == null ? null : currency.toLowerCase(Locale.ROOT),
                subscriptionId,
                instant(PaypalOrder.text(transaction.path("time"))));
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
