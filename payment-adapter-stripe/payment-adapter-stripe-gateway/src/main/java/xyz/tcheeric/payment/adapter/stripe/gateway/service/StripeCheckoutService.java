package xyz.tcheeric.payment.adapter.stripe.gateway.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeValidationException;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutRequest;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;

@RequiredArgsConstructor
public class StripeCheckoutService {

    private final StripeGatewayProperties properties;
    private final StripeCheckoutClient checkoutClient;

    public StripeCheckoutSession createCheckoutSession(Integer amount, String description) {
        validateAmount(amount);
        String quoteId = UUID.randomUUID().toString();
        return createCheckoutSession(quoteId, amount, description);
    }

    public StripeCheckoutSession createCheckoutSession(String quoteId, Integer amount, String description) {
        validateAmount(amount);
        String normalizedCurrency = properties.normalizeCurrency(properties.getDefaultCurrency());
        validateCurrency(normalizedCurrency);

        StripeCheckoutRequest checkoutRequest = StripeCheckoutRequest.builder()
                .quoteId(quoteId)
                .amount(amount)
                .currency(normalizedCurrency)
                .description(sanitizeDescription(description))
                .idempotencyKey("stripe:checkout:" + quoteId)
                .build();
        return checkoutClient.createCheckoutSession(checkoutRequest);
    }

    public StripeCheckoutSession retrieveCheckoutSession(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new StripeValidationException("Stripe sessionId must not be blank");
        }
        return checkoutClient.retrieveCheckoutSession(sessionId);
    }

    public String buildIdempotencyKey(String quoteId) {
        if (StringUtils.isBlank(quoteId)) {
            throw new StripeValidationException("Stripe quoteId must not be blank");
        }
        return "stripe:checkout:" + quoteId;
    }

    private void validateAmount(Integer amount) {
        if (amount == null || amount < properties.getMinAmountMinor()) {
            throw new StripeValidationException("Stripe amount must be >= " + properties.getMinAmountMinor());
        }
        if (amount > properties.getMaxAmountMinor()) {
            throw new StripeValidationException("Stripe amount must be <= " + properties.getMaxAmountMinor());
        }
    }

    private void validateCurrency(String currency) {
        if (!properties.isCurrencyAllowed(currency)) {
            throw new StripeValidationException("Stripe currency is not allowed: " + currency);
        }
    }

    private String sanitizeDescription(String description) {
        String trimmedDescription = StringUtils.defaultIfBlank(description, "Cashu voucher");
        return StringUtils.abbreviate(trimmedDescription.trim(), 255);
    }
}
