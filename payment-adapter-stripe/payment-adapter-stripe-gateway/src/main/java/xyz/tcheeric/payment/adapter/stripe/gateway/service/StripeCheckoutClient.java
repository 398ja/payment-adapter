package xyz.tcheeric.payment.adapter.stripe.gateway.service;

import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutRequest;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;

public interface StripeCheckoutClient {

    StripeCheckoutSession createCheckoutSession(StripeCheckoutRequest checkoutRequest);

    StripeCheckoutSession retrieveCheckoutSession(String sessionId);
}
