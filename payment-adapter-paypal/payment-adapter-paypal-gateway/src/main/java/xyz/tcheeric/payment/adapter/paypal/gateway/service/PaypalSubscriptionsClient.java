package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import java.time.Instant;
import java.util.List;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSubscription;

/**
 * The Catalog Products and Subscriptions calls an instalment plan makes, every one AS THE SELLER
 * ({@code PayPal-Auth-Assertion}): the product, the billing plan and the subscription all live on the stall's own
 * PayPal account, so the stall is merchant of record for every instalment (ADR 0001, ADR 0003). The counterpart of
 * {@code StripeSubscriptionClient}; an interface so tests replace PayPal.
 *
 * @throws xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException from every method when PayPal
 *         refuses or cannot be reached
 */
public interface PaypalSubscriptionsClient {

    /**
     * Creates the product, the billing plan and the subscription for one instalment plan, and returns the subscription
     * the buyer must approve. Every step carries a {@code PayPal-Request-Id} derived from {@code request.planId()}, so a
     * retry answers with the objects the first attempt made rather than making new ones.
     */
    PaypalSubscription startSubscription(PaypalSubscriptionRequest request);

    PaypalSubscription getSubscription(String merchantId, String subscriptionId);

    /**
     * Ends the subscription: PayPal charges nothing more on it. Instalments already paid are not touched. Idempotent:
     * cancelling a subscription that already ended is not an error.
     */
    void cancelSubscription(String merchantId, String subscriptionId, String reason);

    /** The subscription's transactions between the two instants, as PayPal lists them (oldest first). */
    List<PaypalSale> transactions(String merchantId, String subscriptionId, Instant from, Instant to);

    /** One sale, read live: what the operator's check and the stale sweep need for an instalment row. */
    PaypalSale getSale(String merchantId, String saleId);
}
