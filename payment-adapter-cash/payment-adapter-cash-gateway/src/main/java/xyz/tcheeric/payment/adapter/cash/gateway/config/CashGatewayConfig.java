package xyz.tcheeric.payment.adapter.cash.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import xyz.tcheeric.payment.adapter.cash.gateway.CashGateway;
import xyz.tcheeric.payment.adapter.cash.nostr.client.NostrClient;
import xyz.tcheeric.payment.adapter.cash.nostr.crypto.Nip44EncryptionService;
import xyz.tcheeric.payment.adapter.core.common.GatewayRegistry;

/**
 * Configuration for Cash Gateway module.
 *
 * <p>Enables scheduling for background tasks like invoice expiry checking.
 * Registers the CashGateway with the GatewayRegistry.
 */
@Configuration
@EnableScheduling
@ComponentScan(basePackageClasses = CashGateway.class)
public class CashGatewayConfig {

    @Value("${cash.default.relays:wss://relay.damus.io,wss://nos.lol}")
    private String defaultRelays;

    @Bean
    public GatewayRegistry gatewayRegistry(CashGateway cashGateway) {
        GatewayRegistry registry = new GatewayRegistry();
        registry.register(cashGateway);
        return registry;
    }

    @Bean
    public NostrClient nostrClient() {
        return new NostrClient(defaultRelays);
    }

    @Bean
    public Nip44EncryptionService nip44EncryptionService() {
        return new Nip44EncryptionService();
    }
}
