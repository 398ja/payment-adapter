package xyz.tcheeric.payment.adapter.cash.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration for Cash Gateway module.
 *
 * <p>Enables scheduling for background tasks like invoice expiry checking.
 */
@Configuration
@EnableScheduling
public class CashGatewayConfig {
    // Bean definitions can be added here if needed
}
