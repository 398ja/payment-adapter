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

    /**
     * A coupon purchase: a DIRECT charge on the stall's connected account.
     *
     * <p>Separate from {@link #createCheckoutSession(String, Integer, String)}
     * rather than an extra argument on it, because the two answer different
     * questions about who is selling. The mint-quote flow sells Imani's own
     * product and charges the platform; this sells a stall's coupon, and the
     * stall must be merchant of record or Imani becomes the holder of customer
     * money. Keeping them apart means no caller can drift from one to the other
     * by leaving an argument null.
     *
     * <p>The currency is the CALLER'S, not the platform default. A stall issues
     * coupons in one currency and its connected account presents in one
     * currency, and they must agree — checked at Stripe setup, never at
     * checkout, so a mismatch is a stall's configuration problem rather than a
     * customer's failed payment.
     *
     * @param connectedAccountId the stall's {@code acct_…}
     * @param applicationFeeMinor the platform's cut, or null to take nothing
     * @param metadata carried on the session and returned on the webhook; how
     *                 the purchase names the buyer its coupon must reach
     */
    public StripeCheckoutSession createPurchaseSession(
            String quoteId,
            Integer amount,
            String description,
            String currency,
            String connectedAccountId,
            Long applicationFeeMinor,
            java.util.Map<String, String> metadata) {
        validateAmount(amount);
        if (StringUtils.isBlank(connectedAccountId)) {
            throw new StripeValidationException("Stripe connectedAccountId must not be blank for a purchase");
        }
        if (applicationFeeMinor != null) {
            if (applicationFeeMinor < 0) {
                throw new StripeValidationException("Stripe applicationFeeMinor must not be negative");
            }
            // A fee at or above the amount would leave the stall nothing, or
            // leave Stripe to reject it after the customer has committed. Refuse
            // where the caller can still see it.
            if (applicationFeeMinor >= amount.longValue()) {
                throw new StripeValidationException(
                        "Stripe applicationFeeMinor must be less than the amount");
            }
        }
        String normalizedCurrency = properties.normalizeCurrency(currency);
        validateCurrency(normalizedCurrency);

        StripeCheckoutRequest.StripeCheckoutRequestBuilder builder = StripeCheckoutRequest.builder()
                .quoteId(quoteId)
                .amount(amount)
                .currency(normalizedCurrency)
                .description(sanitizeDescription(description))
                .idempotencyKey(buildIdempotencyKey(quoteId))
                .connectedAccountId(connectedAccountId)
                .applicationFeeMinor(applicationFeeMinor);
        if (metadata != null) {
            metadata.forEach(builder::metadata);
        }
        return checkoutClient.createCheckoutSession(builder.build());
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
