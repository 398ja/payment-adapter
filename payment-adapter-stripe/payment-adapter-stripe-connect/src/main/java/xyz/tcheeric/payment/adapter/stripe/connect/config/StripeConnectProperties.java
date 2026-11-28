package xyz.tcheeric.payment.adapter.stripe.connect.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
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
    /** The Connect webhook endpoint's Stripe id ({@code STRIPE_CONNECT_WEBHOOK_ENDPOINT_ID}, #286). */
    private String webhookEndpointId;
    private String country;
    /** Connecting a stall's existing Stripe account through OAuth. Off until both values below are set. */
    private OAuth oauth = new OAuth();

    @PostConstruct
    void normalize() {
        if (country != null && !country.isBlank()) {
            country = country.trim().toUpperCase(Locale.ROOT);
        }
    }

    @Data
    public static class OAuth {
        /**
         * The platform's Connect {@code client_id} ({@code ca_...}), from Connect settings > Onboarding options
         * > OAuth. Public by design: it appears in the authorize URL the farm's browser opens. Live and test mode
         * have different ids.
         */
        private String clientId;
        /**
         * Where Stripe sends the browser after consent. Must exactly match a redirect URI registered in the same
         * settings page, and be HTTPS in live mode. The adapter does not serve this URL: whoever does passes the
         * {@code code} and {@code state} it receives to {@code POST /api/v1/stripe/connect/oauth/complete}.
         */
        private String redirectUri;
        /** How long a farm has to finish Stripe's screens. The authorization code itself lasts five minutes. */
        private Duration stateTtl = Duration.ofMinutes(10);

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank() && redirectUri != null && !redirectUri.isBlank();
        }
    }
}
