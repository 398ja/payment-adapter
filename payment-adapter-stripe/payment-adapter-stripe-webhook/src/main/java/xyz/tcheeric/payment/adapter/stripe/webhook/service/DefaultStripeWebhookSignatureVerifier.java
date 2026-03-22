package xyz.tcheeric.payment.adapter.stripe.webhook.service;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import org.apache.commons.lang3.StringUtils;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookSignatureException;

public class DefaultStripeWebhookSignatureVerifier implements StripeWebhookSignatureVerifier {

    private final String webhookSecret;
    private final long toleranceSeconds;

    public DefaultStripeWebhookSignatureVerifier(String webhookSecret, long toleranceSeconds) {
        this.webhookSecret = webhookSecret;
        this.toleranceSeconds = toleranceSeconds;
    }

    @Override
    public void verify(String rawPayload, String signatureHeader) {
        if (StringUtils.isBlank(signatureHeader)) {
            throw new WebhookSignatureException("Missing Stripe-Signature header");
        }
        if (StringUtils.isBlank(webhookSecret)) {
            throw new WebhookSignatureException("Missing Stripe webhook secret");
        }
        try {
            Webhook.constructEvent(rawPayload, signatureHeader, webhookSecret, toleranceSeconds);
        } catch (SignatureVerificationException e) {
            throw new WebhookSignatureException("Invalid Stripe webhook signature", e);
        }
    }
}
