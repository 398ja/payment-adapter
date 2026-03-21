package xyz.tcheeric.payment.adapter.stripe.connect;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeConnectServiceTest {

    private ConnectedStripeAccountRepository repository;
    private StripeConnectService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConnectedStripeAccountRepository.class);
        service = new StripeConnectService(repository);
    }

    // Verifies linking a merchant account stores the merchant and Stripe identifiers together.
    @Test
    void linksMerchantAccount() {
        when(repository.findByMerchantPubkey("merchant-123")).thenReturn(Optional.empty());
        when(repository.save(any(ConnectedStripeAccount.class))).thenAnswer(invocation -> {
            ConnectedStripeAccount account = invocation.getArgument(0);
            if (account.getCreatedAt() == null) {
                account.setCreatedAt(Instant.now());
            }
            return account;
        });

        ConnectedStripeAccount account = service.linkMerchantAccount("merchant-123", "acct_123", "usd");

        assertEquals("merchant-123", account.getMerchantPubkey());
        assertEquals("acct_123", account.getStripeAccountId());
        assertEquals("usd", account.getDefaultCurrency());
    }
}
