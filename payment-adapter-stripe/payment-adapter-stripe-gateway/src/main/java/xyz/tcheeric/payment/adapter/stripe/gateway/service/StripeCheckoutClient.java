package xyz.tcheeric.payment.adapter.stripe.gateway.service;

import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutRequest;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;

public interface StripeCheckoutClient {

    StripeCheckoutSession createCheckoutSession(StripeCheckoutRequest checkoutRequest);

    /** Retrieve a PLATFORM session. Equivalent to passing a null account. */
    StripeCheckoutSession retrieveCheckoutSession(String sessionId);

    /**
     * Retrieve a session from the account that owns it.
     *
     * <p>A direct-charge session lives on the connected account and is not
     * visible from the platform, so a caller has to name it. Null means the
     * platform, which keeps every existing call site correct.
     */
    StripeCheckoutSession retrieveCheckoutSession(String sessionId, String connectedAccountId);
}
