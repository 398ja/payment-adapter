package xyz.tcheeric.payment.adapter.stripe.connect.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "stripe.connect")
public class StripeConnectProperties {

    private boolean enabled;
    private String refreshUrl;
    private String returnUrl;
    // Never in toString: a logged or exception-rendered properties bean must not carry the secret.
    @ToString.Exclude
    private String webhookSecret;
    private String country;

    @PostConstruct
    void normalize() {
        if (country != null && !country.isBlank()) {
            country = country.trim().toUpperCase(Locale.ROOT);
        }
    }
}
