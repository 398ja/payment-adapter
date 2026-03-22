package xyz.tcheeric.payment.adapter.test.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeAccountSnapshot;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeConnectClient;
import xyz.tcheeric.payment.adapter.stripe.connect.StripeConnectService;
import xyz.tcheeric.payment.adapter.stripe.connect.config.StripeConnectProperties;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectAccountResponse;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;

@Import({StripeConnectService.class, StripeConnectProperties.class, StripeGatewayProperties.class})
class StripeConnectServiceIT extends BasePostgresIT {

    @Autowired
    private StripeConnectService stripeConnectService;

    @Autowired
    private ConnectedStripeAccountRepository connectedStripeAccountRepository;

    @Autowired
    private StripeConnectProperties connectProperties;

    @MockitoBean
    private StripeConnectClient stripeConnectClient;

    @BeforeEach
    void setUp() {
        connectedStripeAccountRepository.deleteAll();
        connectProperties.setEnabled(true);
        connectProperties.setReturnUrl("https://example.com/return");
        connectProperties.setRefreshUrl("https://example.com/refresh");
        connectProperties.setCountry("US");
    }

    // Verifies createOrResume persists a new connected account via the repository.
    @Test
    void createOrResume_newMerchant_persistsAccount() {
        StripeAccountSnapshot snapshot = new StripeAccountSnapshot(
                "merchant-123", "acct_123", false, false, false,
                false, "usd", List.of(), null, "US", "test@example.com");
        when(stripeConnectClient.createConnectedAccount("merchant-123", "US")).thenReturn(snapshot);
        when(stripeConnectClient.createOnboardingLink(eq("acct_123"), anyString(), anyString()))
                .thenReturn("https://connect.stripe.com/onboard");

        StripeConnectAccountResponse response = stripeConnectService.createOrResume(
                "merchant-123", null, null);

        assertThat(response.merchantPubkey()).isEqualTo("merchant-123");
        assertThat(response.stripeAccountId()).isEqualTo("acct_123");
        assertThat(response.onboardingUrl()).isEqualTo("https://connect.stripe.com/onboard");
        assertThat(connectedStripeAccountRepository.findByMerchantPubkey("merchant-123")).isPresent();
    }

    // Verifies createOrResume for an existing merchant refreshes the account from Stripe.
    @Test
    void createOrResume_existingMerchant_refreshesAccount() {
        StripeAccountSnapshot initialSnapshot = new StripeAccountSnapshot(
                "merchant-123", "acct_123", false, false, false,
                false, "usd", List.of(), null, "US", null);
        when(stripeConnectClient.createConnectedAccount("merchant-123", "US")).thenReturn(initialSnapshot);
        when(stripeConnectClient.createOnboardingLink(eq("acct_123"), anyString(), anyString()))
                .thenReturn("https://connect.stripe.com/onboard");
        stripeConnectService.createOrResume("merchant-123", null, null);

        StripeAccountSnapshot refreshedSnapshot = new StripeAccountSnapshot(
                "merchant-123", "acct_123", true, true, true,
                true, "usd", List.of(), null, "US", "test@example.com");
        when(stripeConnectClient.retrieveAccount("acct_123")).thenReturn(refreshedSnapshot);

        StripeConnectAccountResponse response = stripeConnectService.createOrResume(
                "merchant-123", null, null);

        assertThat(response.onboardingComplete()).isTrue();
        assertThat(response.chargesEnabled()).isTrue();
        assertThat(response.payoutsEnabled()).isTrue();
        assertThat(connectedStripeAccountRepository.count()).isEqualTo(1);
    }

    // Verifies getStatus returns not_connected for unknown merchants.
    @Test
    void getStatus_unknownMerchant_returnsNotConnected() {
        StripeConnectAccountResponse response = stripeConnectService.getStatus("merchant-unknown");

        assertThat(response.status()).isEqualTo("not_connected");
        assertThat(response.stripeAccountId()).isNull();
    }

    // Verifies disconnect removes the account from the repository.
    @Test
    void disconnect_existingMerchant_removesAccount() {
        StripeAccountSnapshot snapshot = new StripeAccountSnapshot(
                "merchant-123", "acct_123", true, true, true,
                true, "usd", List.of(), null, "US", null);
        when(stripeConnectClient.createConnectedAccount("merchant-123", "US")).thenReturn(snapshot);
        stripeConnectService.createOrResume("merchant-123", null, null);

        stripeConnectService.disconnect("merchant-123");

        assertThat(connectedStripeAccountRepository.findByMerchantPubkey("merchant-123")).isEmpty();
    }

    // Verifies service methods throw when Stripe Connect is disabled.
    @Test
    void createOrResume_connectDisabled_throws() {
        connectProperties.setEnabled(false);

        assertThatThrownBy(() -> stripeConnectService.createOrResume("merchant-123", null, null))
                .isInstanceOf(StripeConnectException.class);
    }
}
