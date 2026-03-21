package xyz.tcheeric.payment.adapter.test.integration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeConnectService;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(StripeConnectService.class)
class StripeConnectServiceIT extends BasePostgresIT {

    @Autowired
    private StripeConnectService stripeConnectService;

    @Autowired
    private ConnectedStripeAccountRepository connectedStripeAccountRepository;

    @BeforeEach
    void cleanUp() {
        connectedStripeAccountRepository.deleteAll();
    }

    // Verifies linking a merchant account persists the Stripe account mapping.
    @Test
    void linkMerchantAccount_persistsMapping() {
        ConnectedStripeAccount account = stripeConnectService.linkMerchantAccount("merchant-123", "acct_123", "usd");

        ConnectedStripeAccount storedAccount = connectedStripeAccountRepository.findByMerchantPubkey("merchant-123").orElseThrow();

        assertThat(account.getId()).isNotNull();
        assertThat(storedAccount.getStripeAccountId()).isEqualTo("acct_123");
        assertThat(storedAccount.getDefaultCurrency()).isEqualTo("usd");
    }

    // Verifies updating Stripe Connect capabilities persists the new capability flags.
    @Test
    void updateCapabilities_persistsCapabilityFlags() {
        stripeConnectService.linkMerchantAccount("merchant-999", "acct_999", "eur");

        ConnectedStripeAccount updatedAccount = stripeConnectService.updateCapabilities("acct_999", true, true, false);

        assertThat(updatedAccount.isOnboardingComplete()).isTrue();
        assertThat(updatedAccount.isChargesEnabled()).isTrue();
        assertThat(updatedAccount.isPayoutsEnabled()).isFalse();
    }

    // Verifies relinking the same merchant updates the existing account instead of creating duplicates.
    @Test
    void linkMerchantAccount_existingMerchant_updatesExistingRecord() {
        stripeConnectService.linkMerchantAccount("merchant-123", "acct_123", "usd");

        ConnectedStripeAccount updatedAccount = stripeConnectService.linkMerchantAccount("merchant-123", "acct_456", "eur");

        assertThat(connectedStripeAccountRepository.count()).isEqualTo(1);
        assertThat(updatedAccount.getStripeAccountId()).isEqualTo("acct_456");
        assertThat(updatedAccount.getDefaultCurrency()).isEqualTo("eur");
    }

    // Verifies capability updates reject unknown Stripe accounts instead of silently creating inconsistent state.
    @Test
    void updateCapabilities_unknownAccount_throwsIllegalStateException() {
        assertThatThrownBy(() -> stripeConnectService.updateCapabilities("acct_missing", true, true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acct_missing");
    }
}
