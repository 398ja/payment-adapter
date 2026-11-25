package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalCheckoutCreationException;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalValidationException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrderRequest;

/**
 * A coupon purchase on PayPal: an order paying the stall's own PayPal account, the counterpart of
 * {@code StripeCheckoutService#createPurchaseSession}. The same refusals, in the same order, before
 * PayPal is called, so no buyer meets a payment page that fails after they have committed.
 */
public class PaypalCheckoutService {

    private final PaypalGatewayProperties properties;
    private final PaypalOrdersClient orders;

    public PaypalCheckoutService(PaypalGatewayProperties properties, PaypalOrdersClient orders) {
        this.properties = properties;
        this.orders = orders;
    }

    public PaypalGatewayProperties properties() {
        return properties;
    }

    /**
     * @param merchantId     the stall's PayPal merchant id, payee and the seller acted for
     * @param platformFeeMinor the platform's cut, or null to take nothing
     * @param successUrl     where the buyer lands once paid, or null for {@code paypal.success-url}
     * @param cancelUrl      where a buyer who cancels lands, or null for {@code paypal.cancel-url}
     * @throws PaypalValidationException       refused before PayPal is called
     * @throws PaypalCheckoutCreationException PayPal refused or could not be reached
     */
    public PaypalOrder createPurchaseOrder(String quoteId, long amountMinor, String description, String currency,
            String merchantId, Long platformFeeMinor, String successUrl, String cancelUrl) {
        validateAmount(amountMinor);
        if (merchantId == null || merchantId.isBlank()) {
            throw new PaypalValidationException("PayPal merchantId must not be blank for a purchase");
        }
        if (platformFeeMinor != null) {
            if (platformFeeMinor < 0) {
                throw new PaypalValidationException("PayPal platformFeeMinor must not be negative");
            }
            if (platformFeeMinor >= amountMinor) {
                throw new PaypalValidationException("PayPal platformFeeMinor must be less than the amount");
            }
        }
        String normalized = properties.normalizeCurrency(currency);
        if (!properties.isCurrencyAllowed(normalized)) {
            throw new PaypalValidationException("PayPal currency is not allowed: " + normalized);
        }

        String landing = blank(successUrl) ? properties.getSuccessUrl() : successUrl;
        PaypalOrderRequest request = PaypalOrderRequest.builder()
                .quoteId(quoteId)
                .amountMinor(amountMinor)
                .currency(normalized)
                .description(sanitizeDescription(description))
                .merchantId(merchantId.trim())
                .platformFeeMinor(platformFeeMinor)
                .customId(customId(quoteId))
                .returnUrl(properties.resolvedReturnUrl(landing))
                .cancelUrl(blank(cancelUrl) ? properties.getCancelUrl() : cancelUrl)
                .brandName(properties.getBrandName())
                .requestId(requestId(quoteId))
                .build();
        try {
            PaypalOrder order = orders.createOrder(request);
            if (order.id() == null || order.approveUrl() == null) {
                throw new PaypalApiException("PayPal created an order without an id or an approval link", 502,
                        null, null, null);
            }
            return order;
        } catch (PaypalValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PaypalCheckoutCreationException(quoteId, normalized, e);
        }
    }

    /**
     * {@code <environment>:<quote>}, as Stripe stamps {@code imani_env}: the ledger reads an order
     * naming another deployment as foreign. The quote alone when no environment is named.
     */
    public String customId(String quoteId) {
        String environment = properties.resolvedEnvironment();
        return environment == null ? quoteId : environment + ":" + quoteId;
    }

    /** Idempotency of the create, as {@code stripe:checkout:<quote>} is for Stripe. */
    public String requestId(String quoteId) {
        if (blank(quoteId)) {
            throw new PaypalValidationException("PayPal quoteId must not be blank");
        }
        return "paypal:order:" + quoteId;
    }

    /** One capture per order, whoever asks: the webhook, the buyer's return or the reconciler. */
    public static String captureRequestId(String orderId) {
        return "paypal:capture:" + orderId;
    }

    private void validateAmount(long amount) {
        if (amount < properties.getMinAmountMinor()) {
            throw new PaypalValidationException("PayPal amount must be >= " + properties.getMinAmountMinor());
        }
        if (amount > properties.getMaxAmountMinor()) {
            throw new PaypalValidationException("PayPal amount must be <= " + properties.getMaxAmountMinor());
        }
    }

    /** PayPal caps a purchase unit description at 127 characters. */
    private static String sanitizeDescription(String description) {
        String text = blank(description) ? "Cashu voucher" : description.trim();
        return text.length() > 127 ? text.substring(0, 124) + "..." : text;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
