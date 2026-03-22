package xyz.tcheeric.payment.adapter.stripe.connect;

import com.stripe.model.Event;

public interface StripeConnectClient {

    StripeAccountSnapshot createConnectedAccount(String merchantPubkey, String country);

    StripeAccountSnapshot retrieveAccount(String stripeAccountId);

    String createOnboardingLink(String stripeAccountId, String returnUrl, String refreshUrl);

    Event constructWebhookEvent(String payload, String signatureHeader, String webhookSecret);
}
