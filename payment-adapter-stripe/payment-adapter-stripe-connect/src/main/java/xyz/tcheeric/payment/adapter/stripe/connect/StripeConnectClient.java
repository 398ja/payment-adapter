package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.model.Event;

public interface StripeConnectClient {

    StripeAccountSnapshot createConnectedAccount(String merchantPubkey, String country);

    StripeAccountSnapshot retrieveAccount(String stripeAccountId);

    String createOnboardingLink(String stripeAccountId, String returnUrl, String refreshUrl);

    /**
     * The Stripe page a farm opens to connect its own account. {@code scope} is always {@code read_write}:
     * Stripe refuses anything else for a platform.
     */
    String oauthAuthorizeUrl(String clientId, String redirectUri, String state);

    /**
     * Turns the one-time authorization code into the connected account's id. <b>Makes exactly one attempt</b>:
     * Stripe revokes the connection if a code is used twice, so a retry after a timeout would undo the very
     * connection it was trying to make.
     *
     * @throws xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException
     *         {@code OAUTH_CODE_REJECTED} when Stripe refuses the code, {@code STRIPE_API_ERROR} otherwise
     */
    String exchangeOAuthCode(String code);

    /**
     * Removes Imani from an OAuth-connected account's connected apps, so Imani's platform key can no longer act on
     * it. Used on Stop for accounts the stall owned before connecting.
     *
     * @throws xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException when Stripe refuses or
     *         cannot be reached
     */
    void deauthorize(String clientId, String stripeAccountId);

    Event constructWebhookEvent(String payload, String signatureHeader, String webhookSecret);

    /**
     * Read a PaymentIntent on a connected account ({@code Stripe-Account}). Empty when that account
     * has no such PaymentIntent, which is also how Stripe answers for one that belongs elsewhere.
     *
     * @throws xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException when
     *         Stripe could not be asked
     */
    java.util.Optional<PaymentIntentSnapshot> retrievePaymentIntent(String stripeAccountId, String paymentIntentId);
}
