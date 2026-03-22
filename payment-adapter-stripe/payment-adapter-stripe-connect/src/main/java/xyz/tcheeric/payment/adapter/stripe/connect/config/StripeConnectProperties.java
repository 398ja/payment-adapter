package xyz.tcheeric.payment.adapter.stripe.connect.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "stripe.connect")
public class StripeConnectProperties {

    private boolean enabled;
    private String refreshUrl;
    private String returnUrl;
    private String webhookSecret;
    private String country;

    @PostConstruct
    void normalize() {
        if (country != null && !country.isBlank()) {
            country = country.trim().toUpperCase();
        }
    }
}
