package xyz.tcheeric.payment.adapter.stripe.connect.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "stripe.connect")
public class StripeConnectProperties {

    private boolean enabled;
    private String refreshUrl;
    private String returnUrl;
}
