package xyz.tcheeric.payment.adapter.test.e2e.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import xyz.tcheeric.payment.adapter.cash.nostr.client.NostrClient;
import xyz.tcheeric.payment.adapter.cash.nostr.crypto.Nip44EncryptionService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestConfiguration
public class MockNostrConfig {

    @Bean
    @Primary
    public NostrClient nostrClient() {
        NostrClient client = mock(NostrClient.class);
        doNothing().when(client).publish(any(), anyList());
        when(client.isRunning()).thenReturn(false);
        return client;
    }

    @Bean
    @Primary
    public Nip44EncryptionService nip44EncryptionService() {
        return new Nip44EncryptionService();
    }
}
