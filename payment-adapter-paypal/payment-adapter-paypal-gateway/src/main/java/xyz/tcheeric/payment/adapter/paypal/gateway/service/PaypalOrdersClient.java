package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import java.util.List;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrderRequest;

/**
 * The Orders, Payments and Disputes calls a card purchase makes, every one as the seller
 * ({@code PayPal-Auth-Assertion}). The counterpart of {@code StripeCheckoutClient}; an interface so
 * tests replace PayPal, as they replace Stripe.
 *
 * @throws xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException from every method
 *         when PayPal refuses or cannot be reached
 */
public interface PaypalOrdersClient {

    PaypalOrder createOrder(PaypalOrderRequest request);

    PaypalOrder getOrder(String merchantId, String orderId);

    /**
     * Capture an approved order. Idempotent by {@code requestId}: the same id answers the same
     * capture, so the webhook, the buyer's return and the reconciler can all ask.
     */
    PaypalOrder captureOrder(String merchantId, String orderId, String requestId);

    PaypalCapture getCapture(String merchantId, String captureId);

    /** Ids of every dispute PayPal holds against this capture, open or closed. */
    List<String> disputesFor(String merchantId, String captureId);
}
