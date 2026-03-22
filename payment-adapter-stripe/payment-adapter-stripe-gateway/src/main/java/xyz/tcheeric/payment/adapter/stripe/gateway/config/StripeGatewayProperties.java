package xyz.tcheeric.payment.adapter.stripe.gateway.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "stripe")
public class StripeGatewayProperties {

    private boolean enabled;
    private String secretKey;
    private String successUrl;
    private String cancelUrl;
    private String defaultCurrency = "usd";
    private List<String> allowedCurrencies = new ArrayList<>(List.of("usd"));

    @Min(60)
    private int checkoutExpirySeconds = 1800;

    @Min(1)
    private int webhookToleranceSeconds = 300;

    @Min(1)
    private int minAmountMinor = 1;

    @Min(1)
    private int maxAmountMinor = Integer.MAX_VALUE;

    @PostConstruct
    void validate() {
        defaultCurrency = normalizeCurrency(defaultCurrency);
        allowedCurrencies = allowedCurrencies.stream()
                .map(this::normalizeCurrency)
                .distinct()
                .toList();

        if (!allowedCurrencies.contains(defaultCurrency)) {
            allowedCurrencies = new ArrayList<>(allowedCurrencies);
            allowedCurrencies.add(defaultCurrency);
        }

        if (!enabled) {
            return;
        }

        requireValue(secretKey, "stripe.secret-key");
        requireValue(successUrl, "stripe.success-url");
        requireValue(cancelUrl, "stripe.cancel-url");
    }

    public boolean isCurrencyAllowed(String currency) {
        return allowedCurrencies.contains(normalizeCurrency(currency));
    }

    public String normalizeCurrency(String currency) {
        return currency == null ? null : currency.trim().toLowerCase(Locale.ROOT);
    }

    private void requireValue(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required Stripe property: " + propertyName);
        }
    }
}
