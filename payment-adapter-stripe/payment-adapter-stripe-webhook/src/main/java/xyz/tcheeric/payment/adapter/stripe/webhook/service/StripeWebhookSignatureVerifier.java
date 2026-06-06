package xyz.tcheeric.payment.adapter.stripe.webhook.service;

public interface StripeWebhookSignatureVerifier {

    void verify(String rawPayload, String signatureHeader);
}
