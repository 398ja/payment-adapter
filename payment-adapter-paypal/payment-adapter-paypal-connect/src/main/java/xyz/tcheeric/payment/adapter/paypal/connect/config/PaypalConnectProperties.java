package xyz.tcheeric.payment.adapter.paypal.connect.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Seller onboarding, the counterpart of {@code StripeConnectProperties}. */
@Data
@ConfigurationProperties(prefix = "paypal.connect")
public class PaypalConnectProperties {

    private boolean enabled;

    /** Where PayPal returns a seller after its onboarding flow, when the request names none. */
    private String returnUrl;

    /** Kept for the same request shape as Stripe's; PayPal has no refresh link, so it is unused. */
    private String refreshUrl;

    /** The platform's own PayPal merchant id: the {@code partner_id} in every seller-status call. */
    private String partnerMerchantId;

    /** The onboarding webhook's PayPal id ({@code PAYPAL_CONNECT_WEBHOOK_ID}), part of each signed message. */
    private String webhookId;

    /**
     * What the seller is asked to accept. {@code EXPRESS_CHECKOUT} is PayPal Checkout, which offers a
     * guest card form on PayPal's own page: no card data ever reaches this service (ADR 0002).
     */
    private List<String> products = new ArrayList<>(List.of("EXPRESS_CHECKOUT"));

    /**
     * The permissions the seller grants this platform. {@code PAYMENT} is required to sell;
     * {@code REFUND} lets the operator refund; add {@code PARTNER_FEE} only with PayPal's approval and
     * a fee configured, or onboarding fails.
     */
    private List<String> features = new ArrayList<>(List.of("PAYMENT", "REFUND"));

    @PostConstruct
    void normalize() {
        products = products.stream().map(p -> p.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        features = features.stream().map(f -> f.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (!enabled) {
            return;
        }
        if (partnerMerchantId == null || partnerMerchantId.isBlank()) {
            throw new IllegalStateException("paypal.connect.enabled=true requires paypal.connect.partner-merchant-id: "
                    + "without the platform's own merchant id no seller's status can be read, so none could "
                    + "ever be shown as ready to take cards.");
        }
        if (!features.contains("PAYMENT")) {
            throw new IllegalStateException("paypal.connect.features must include PAYMENT: without it no order "
                    + "can be created for any seller.");
        }
    }
}
