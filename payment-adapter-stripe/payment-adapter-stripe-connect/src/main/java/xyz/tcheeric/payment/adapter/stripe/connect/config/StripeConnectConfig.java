package xyz.tcheeric.payment.adapter.stripe.connect.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeConnectClient;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeSdkConnectClient;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

@Configuration
@EnableConfigurationProperties({StripeConnectProperties.class, StripeGatewayProperties.class})
public class StripeConnectConfig {

    @Bean
    StripeConnectClient stripeConnectClient(StripeGatewayProperties gatewayProperties) {
        return new StripeSdkConnectClient(gatewayProperties);
    }
}
