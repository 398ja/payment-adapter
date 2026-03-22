package xyz.tcheeric.payment.adapter.stripe.connect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Account;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ProcessedStripeWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.stripe.connect.config.StripeConnectProperties;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectAccountResponse;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectWebhookResponse;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeConnectServiceTest {

    @Mock
    private ConnectedStripeAccountRepository accountRepository;

    @Mock
    private ProcessedStripeWebhookEventRepository processedEventRepository;

    @Mock
    private StripeConnectClient stripeConnectClient;

    private StripeConnectService service;

    @BeforeEach
    void setUp() {
        StripeConnectProperties connectProperties = new StripeConnectProperties();
        connectProperties.setEnabled(true);
        connectProperties.setWebhookSecret("whsec_test");
        connectProperties.setReturnUrl("https://merchant.example/settings");
        connectProperties.setRefreshUrl("https://merchant.example/settings");
        connectProperties.setCountry("US");

        StripeGatewayProperties gatewayProperties = new StripeGatewayProperties();
        gatewayProperties.setDefaultCurrency("usd");

        service = new StripeConnectService(
                connectProperties,
                gatewayProperties,
                stripeConnectClient,
                new ObjectMapper(),
                accountRepository,
                processedEventRepository);
    }

    @Test
    void createOrResumeCreatesAccountAndOnboardingLink() {
        StripeAccountSnapshot snapshot = new StripeAccountSnapshot(
                "merchant-123",
                "acct_123",
                false,
                false,
                false,
                false,
                "usd",
                List.of("external_account"),
                null,
                "US",
                "merchant@example.com");
        when(accountRepository.findByMerchantPubkey("merchant-123")).thenReturn(Optional.empty());
        when(stripeConnectClient.createConnectedAccount("merchant-123", "US")).thenReturn(snapshot);
        when(stripeConnectClient.createOnboardingLink("acct_123",
                "https://merchant.example/settings",
                "https://merchant.example/settings"))
                .thenReturn("https://connect.stripe.test/onboarding");
        when(accountRepository.save(any(ConnectedStripeAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StripeConnectAccountResponse response = service.createOrResume("merchant-123", null, null);

        assertThat(response.stripeAccountId()).isEqualTo("acct_123");
        assertThat(response.onboardingUrl()).isEqualTo("https://connect.stripe.test/onboarding");
        assertThat(response.status()).isEqualTo("onboarding_in_progress");
        assertThat(response.requirementsDue()).containsExactly("external_account");
    }

    @Test
    void createOrResumeRefreshesExistingAccountWithoutCreatingSecondAccount() {
        ConnectedStripeAccount existing = existingAccount();
        StripeAccountSnapshot snapshot = new StripeAccountSnapshot(
                "merchant-123",
                "acct_123",
                false,
                false,
                false,
                true,
                "usd",
                List.of("individual.verification.document"),
                null,
                "US",
                "merchant@example.com");

        when(accountRepository.findByMerchantPubkey("merchant-123"))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.of(existing));
        when(stripeConnectClient.retrieveAccount("acct_123")).thenReturn(snapshot);
        when(stripeConnectClient.createOnboardingLink(eq("acct_123"), any(), any()))
                .thenReturn("https://connect.stripe.test/resume");
        when(accountRepository.save(any(ConnectedStripeAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StripeConnectAccountResponse response = service.createOrResume("merchant-123", null, null);

        verify(stripeConnectClient, never()).createConnectedAccount(any(), any());
        assertThat(response.onboardingUrl()).isEqualTo("https://connect.stripe.test/resume");
        assertThat(response.status()).isEqualTo("restricted");
        assertThat(response.requirementsDue()).containsExactly("individual.verification.document");
    }

    @Test
    void createOrResumeRejectsOwnershipMismatch() {
        StripeAccountSnapshot snapshot = new StripeAccountSnapshot(
                "someone-else",
                "acct_123",
                false,
                false,
                false,
                false,
                "usd",
                List.of(),
                null,
                "US",
                null);
        when(accountRepository.findByMerchantPubkey("merchant-123")).thenReturn(Optional.empty());
        when(stripeConnectClient.createConnectedAccount("merchant-123", "US")).thenReturn(snapshot);

        assertThrows(StripeConnectException.class, () -> service.createOrResume("merchant-123", null, null));
    }

    @Test
    void handleWebhookUpdatesPersistedStatus() {
        Account account = new Account();
        account.setId("acct_123");
        account.setChargesEnabled(Boolean.TRUE);
        account.setPayoutsEnabled(Boolean.FALSE);
        account.setDetailsSubmitted(Boolean.TRUE);
        account.setDefaultCurrency("usd");
        account.setCountry("US");
        account.setEmail("merchant@example.com");
        account.setMetadata(java.util.Map.of("merchant_pubkey", "merchant-123"));
        Account.Requirements requirements = new Account.Requirements();
        requirements.setCurrentlyDue(List.of("external_account"));
        account.setRequirements(requirements);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(account));

        Event event = mock(Event.class);
        when(event.getId()).thenReturn("evt_123");
        when(event.getType()).thenReturn("account.updated");
        when(event.getLivemode()).thenReturn(Boolean.FALSE);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);

        when(stripeConnectClient.constructWebhookEvent("{}", "sig", "whsec_test")).thenReturn(event);
        when(processedEventRepository.findById("evt_123")).thenReturn(Optional.empty());
        when(processedEventRepository.save(any(ProcessedStripeWebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.findByStripeAccountId("acct_123")).thenReturn(Optional.empty());
        when(accountRepository.findByMerchantPubkey("merchant-123")).thenReturn(Optional.empty());
        when(accountRepository.save(any(ConnectedStripeAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StripeConnectWebhookResponse response = service.handleWebhook("{}", "sig");

        assertThat(response.status()).isEqualTo("processed");
        verify(accountRepository).save(any(ConnectedStripeAccount.class));
    }

    private ConnectedStripeAccount existingAccount() {
        ConnectedStripeAccount account = new ConnectedStripeAccount();
        account.setMerchantPubkey("merchant-123");
        account.setStripeAccountId("acct_123");
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        return account;
    }
}
