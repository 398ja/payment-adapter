package xyz.tcheeric.payment.adapter.paypal.gateway.model;

import lombok.Builder;
import lombok.Value;

/**
 * A purchase order to create, the counterpart of {@code StripeCheckoutRequest}. No metadata map:
 * PayPal carries {@code custom_id} only, so the buyer and the rest live in {@code paypal_checkout}.
 */
@Value
@Builder
public class PaypalOrderRequest {
    String quoteId;
    long amountMinor;
    String currency;
    String description;
    /** The seller ({@code payee.merchant_id}) and on whose behalf the order is created. */
    String merchantId;
    /** The platform's cut, or null. Needs the seller's {@code PARTNER_FEE} consent. */
    Long platformFeeMinor;
    /** {@code <environment>:<quote id>}, or the quote id alone when no environment is named. */
    String customId;
    String returnUrl;
    String cancelUrl;
    String brandName;
    /** {@code PayPal-Request-Id}: the same key on a retry yields the same order. */
    String requestId;
}
