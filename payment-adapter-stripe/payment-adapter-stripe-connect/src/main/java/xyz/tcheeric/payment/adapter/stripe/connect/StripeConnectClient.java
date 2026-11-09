package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.model.Event;

public interface StripeConnectClient {

    StripeAccountSnapshot createConnectedAccount(String merchantPubkey, String country);

    StripeAccountSnapshot retrieveAccount(String stripeAccountId);

    String createOnboardingLink(String stripeAccountId, String returnUrl, String refreshUrl);

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
