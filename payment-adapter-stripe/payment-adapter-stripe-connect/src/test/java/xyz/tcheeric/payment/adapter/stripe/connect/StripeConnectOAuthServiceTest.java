package xyz.tcheeric.payment.adapter.stripe.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeConnectionType;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeOAuthState;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripeOAuthStateRepository;
import xyz.tcheeric.payment.adapter.stripe.connect.config.StripeConnectProperties;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectExceptionCode;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectAccountResponse;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectOAuthStartResponse;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;

/**
 * Connecting a stall's EXISTING Stripe account through OAuth. What is pinned here is who the account is
 * attached to, and that an authorization code can never be spent twice: Stripe revokes the connection if it is.
 */
@ExtendWith(MockitoExtension.class)
class StripeConnectOAuthServiceTest {

    private static final String MERCHANT = "npub_farm";
    private static final String ACCOUNT = "acct_farm";

    @Mock private ConnectedStripeAccountRepository accounts;
    @Mock private ProcessedStripeWebhookEventRepository processedEvents;
    @Mock private StripeOAuthStateRepository states;
    @Mock private StripeConnectClient client;

    private StripeConnectProperties props;
    private StripeConnectService service;

    @BeforeEach
    void setUp() {
        props = new StripeConnectProperties();
        props.setEnabled(true);
        props.getOauth().setClientId("ca_test");
        props.getOauth().setRedirectUri("https://wallet.example/stripe/connected");
        StripeGatewayProperties gateway = new StripeGatewayProperties();
        gateway.setDefaultCurrency("gbp");
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        lenient().when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new StripeConnectService(
                props, gateway, client, new ObjectMapper(), accounts, processedEvents, states, tx);
        lenient().when(accounts.save(any(ConnectedStripeAccount.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static StripeAccountSnapshot snapshot(String id, String merchantMetadata, boolean chargesEnabled) {
        return new StripeAccountSnapshot(
                merchantMetadata, id, chargesEnabled, chargesEnabled, true, true, "gbp",
                List.of(), null, "GB", "farm@example.com", "standard", "account", "stripe", "full");
    }

    private void stateIsValidFor(String state, String merchant) {
        when(states.consume(eq(state), any(Instant.class))).thenReturn(1);
        StripeOAuthState row = new StripeOAuthState();
        row.setState(state);
        row.setMerchantPubkey(merchant);
        when(states.findById(state)).thenReturn(Optional.of(row));
    }

    // ---- start ----

    /** The URL carries the state we stored, and the stored row is bound to the merchant and expires. */
    @Test
    void startStoresAStateAndPutsTheSameOneInTheUrl() {
        when(accounts.findByMerchantPubkey(MERCHANT)).thenReturn(Optional.empty());
        when(client.oauthAuthorizeUrl(eq("ca_test"), eq("https://wallet.example/stripe/connected"), anyString()))
                .thenReturn("https://connect.stripe.com/oauth/authorize?x=1");

        StripeConnectOAuthStartResponse response = service.startOAuth(MERCHANT);

        ArgumentCaptor<StripeOAuthState> saved = ArgumentCaptor.forClass(StripeOAuthState.class);
        verify(states).save(saved.capture());
        ArgumentCaptor<String> inUrl = ArgumentCaptor.forClass(String.class);
        verify(client).oauthAuthorizeUrl(anyString(), anyString(), inUrl.capture());
        assertThat(saved.getValue().getState()).isEqualTo(inUrl.getValue()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(saved.getValue().getMerchantPubkey()).isEqualTo(MERCHANT);
        assertThat(saved.getValue().getConsumedAt()).isNull();
        assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now());
        assertThat(response.authorizeUrl()).startsWith("https://connect.stripe.com/");
        assertThat(response.expiresAt()).isEqualTo(saved.getValue().getExpiresAt());
    }

    /** Two flows must not share a state, or one farm's callback could complete another's. */
    @Test
    void startGivesEveryFlowItsOwnState() {
        when(accounts.findByMerchantPubkey(anyString())).thenReturn(Optional.empty());
        when(client.oauthAuthorizeUrl(anyString(), anyString(), anyString())).thenReturn("u");

        service.startOAuth("npub_a");
        service.startOAuth("npub_b");

        ArgumentCaptor<StripeOAuthState> saved = ArgumentCaptor.forClass(StripeOAuthState.class);
        verify(states, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getState()).isNotEqualTo(saved.getAllValues().get(1).getState());
    }

    /** Without a client id and redirect URI there is nothing to send the farm to. */
    @Test
    void startRefusedWhenOAuthIsNotConfigured() {
        props.getOauth().setClientId(null);

        assertThatThrownBy(() -> service.startOAuth(MERCHANT))
                .isInstanceOfSatisfying(StripeConnectException.class,
                        e -> assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.OAUTH_DISABLED));
        verify(states, never()).save(any());
    }

    /** A merchant with an account is turned away before the farm consents to something we would reject. */
    @Test
    void startRefusedWhenTheMerchantAlreadyHasAnAccount() {
        when(accounts.findByMerchantPubkey(MERCHANT)).thenReturn(Optional.of(new ConnectedStripeAccount()));

        assertThatThrownBy(() -> service.startOAuth(MERCHANT))
                .isInstanceOfSatisfying(StripeConnectException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.MERCHANT_ALREADY_LINKED);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(states, never()).save(any());
        verify(client, never()).oauthAuthorizeUrl(anyString(), anyString(), anyString());
    }

    // ---- complete ----

    /** The happy path: the merchant comes from the stored state, and the account is marked OAUTH. */
    @Test
    void completeConnectsTheAccountToTheMerchantWhoStartedTheFlow() {
        stateIsValidFor("st1", MERCHANT);
        when(client.exchangeOAuthCode("code1")).thenReturn(ACCOUNT);
        when(client.retrieveAccount(ACCOUNT)).thenReturn(snapshot(ACCOUNT, null, true));

        StripeConnectAccountResponse response = service.completeOAuth("code1", "st1");

        ArgumentCaptor<ConnectedStripeAccount> saved = ArgumentCaptor.forClass(ConnectedStripeAccount.class);
        verify(accounts, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        ConnectedStripeAccount row = saved.getValue();
        assertThat(row.getMerchantPubkey()).isEqualTo(MERCHANT);
        assertThat(row.getStripeAccountId()).isEqualTo(ACCOUNT);
        assertThat(row.getConnectionType()).isEqualTo(StripeConnectionType.OAUTH);
        assertThat(row.getAccountType()).isEqualTo("standard");
        assertThat(row.getFeePayer()).isEqualTo("account");
        assertThat(row.getLossesPayer()).isEqualTo("stripe");
        assertThat(row.getDashboardType()).isEqualTo("full");
        assertThat(response.merchantPubkey()).isEqualTo(MERCHANT);
        assertThat(response.stripeAccountId()).isEqualTo(ACCOUNT);
        assertThat(response.chargesEnabled()).isTrue();
    }

    /** An unknown, expired or already-used state never reaches Stripe: no code is spent on it. */
    @Test
    void completeWithAnInvalidStateNeverExchangesTheCode() {
        when(states.consume(eq("bad"), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> service.completeOAuth("code1", "bad"))
                .isInstanceOfSatisfying(StripeConnectException.class,
                        e -> assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.OAUTH_STATE_INVALID));
        verify(client, never()).exchangeOAuthCode(anyString());
        verify(accounts, never()).save(any());
    }

    /** Missing or absurd inputs are refused before any database or Stripe call. */
    @Test
    void completeRefusesBlankAndOversizedInputs() {
        for (String[] bad : new String[][] {
                {null, "st"}, {"", "st"}, {"code", null}, {"code", ""}, {"x".repeat(257), "st"}, {"code", "s".repeat(65)}}) {
            assertThatThrownBy(() -> service.completeOAuth(bad[0], bad[1]))
                    .isInstanceOfSatisfying(StripeConnectException.class,
                            e -> assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.OAUTH_STATE_INVALID));
        }
        verify(states, never()).consume(anyString(), any());
        verify(client, never()).exchangeOAuthCode(anyString());
    }

    /**
     * The state is spent BEFORE the code is exchanged. If the exchange or anything after it fails, the same state
     * cannot be replayed to spend the same code a second time, which would make Stripe revoke the connection.
     */
    @Test
    void completeSpendsTheStateBeforeExchangingTheCode() {
        stateIsValidFor("st1", MERCHANT);
        when(client.exchangeOAuthCode("code1")).thenThrow(StripeConnectException.apiError("boom", null));

        assertThatThrownBy(() -> service.completeOAuth("code1", "st1")).isInstanceOf(StripeConnectException.class);

        InOrder order = inOrder(states, client);
        order.verify(states).consume(eq("st1"), any(Instant.class));
        order.verify(client).exchangeOAuthCode("code1");
        verify(accounts, never()).save(any());
    }

    /** A Stripe account can belong to one merchant. A second claim is refused, not overwritten. */
    @Test
    void completeRefusesAnAccountAlreadyConnectedToAnotherMerchant() {
        stateIsValidFor("st1", MERCHANT);
        when(client.exchangeOAuthCode("code1")).thenReturn(ACCOUNT);
        when(client.retrieveAccount(ACCOUNT)).thenReturn(snapshot(ACCOUNT, null, true));
        ConnectedStripeAccount other = new ConnectedStripeAccount();
        other.setMerchantPubkey("npub_someone_else");
        other.setStripeAccountId(ACCOUNT);
        when(accounts.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.completeOAuth("code1", "st1"))
                .isInstanceOfSatisfying(StripeConnectException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.MERCHANT_ALREADY_LINKED);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(accounts, never()).save(any());
    }

    /** A merchant already holding a different account must disconnect it first. */
    @Test
    void completeRefusesWhenTheMerchantAlreadyHasADifferentAccount() {
        stateIsValidFor("st1", MERCHANT);
        when(client.exchangeOAuthCode("code1")).thenReturn(ACCOUNT);
        when(client.retrieveAccount(ACCOUNT)).thenReturn(snapshot(ACCOUNT, null, true));
        ConnectedStripeAccount mine = new ConnectedStripeAccount();
        mine.setMerchantPubkey(MERCHANT);
        mine.setStripeAccountId("acct_old");
        when(accounts.findByMerchantPubkey(MERCHANT)).thenReturn(Optional.of(mine));

        assertThatThrownBy(() -> service.completeOAuth("code1", "st1"))
                .isInstanceOfSatisfying(StripeConnectException.class,
                        e -> assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.MERCHANT_ALREADY_LINKED));
        verify(accounts, never()).save(any());
    }

    /** Connecting the same account again is harmless: the row is refreshed, not duplicated or refused. */
    @Test
    void completeIsIdempotentForTheSameMerchantAndAccount() {
        stateIsValidFor("st1", MERCHANT);
        when(client.exchangeOAuthCode("code1")).thenReturn(ACCOUNT);
        when(client.retrieveAccount(ACCOUNT)).thenReturn(snapshot(ACCOUNT, null, true));
        ConnectedStripeAccount mine = new ConnectedStripeAccount();
        mine.setMerchantPubkey(MERCHANT);
        mine.setStripeAccountId(ACCOUNT);
        when(accounts.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(mine));
        when(accounts.findByMerchantPubkey(MERCHANT)).thenReturn(Optional.of(mine));

        StripeConnectAccountResponse response = service.completeOAuth("code1", "st1");

        assertThat(response.stripeAccountId()).isEqualTo(ACCOUNT);
        assertThat(mine.getConnectionType()).isEqualTo(StripeConnectionType.OAUTH);
    }

    /** An account Imani created for another merchant cannot be claimed through someone else's OAuth. */
    @Test
    void completeRefusesAnAccountStampedWithAnotherMerchantsKey() {
        stateIsValidFor("st1", MERCHANT);
        when(client.exchangeOAuthCode("code1")).thenReturn(ACCOUNT);
        when(client.retrieveAccount(ACCOUNT)).thenReturn(snapshot(ACCOUNT, "npub_someone_else", true));

        assertThatThrownBy(() -> service.completeOAuth("code1", "st1"))
                .isInstanceOfSatisfying(StripeConnectException.class,
                        e -> assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.OWNERSHIP_MISMATCH));
        verify(accounts, never()).save(any());
    }

    /** A farm whose account cannot take cards yet is still connected; its status says what is left. */
    @Test
    void completeStoresAnAccountThatCannotTakeCardsYet() {
        stateIsValidFor("st1", MERCHANT);
        when(client.exchangeOAuthCode("code1")).thenReturn(ACCOUNT);
        when(client.retrieveAccount(ACCOUNT)).thenReturn(snapshot(ACCOUNT, null, false));

        StripeConnectAccountResponse response = service.completeOAuth("code1", "st1");

        assertThat(response.chargesEnabled()).isFalse();
        assertThat(response.merchantPubkey()).isEqualTo(MERCHANT);
    }

    /**
     * Two callbacks racing for one account both pass the pre-checks; the database's unique constraint decides.
     * The loser must get the ordinary "already linked" answer, not an unhandled 500.
     */
    @Test
    void completeMapsAUniqueConstraintRaceToAlreadyLinked() {
        stateIsValidFor("st1", MERCHANT);
        when(client.exchangeOAuthCode("code1")).thenReturn(ACCOUNT);
        when(client.retrieveAccount(ACCOUNT)).thenReturn(snapshot(ACCOUNT, null, true));
        when(accounts.save(any(ConnectedStripeAccount.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> service.completeOAuth("code1", "st1"))
                .isInstanceOfSatisfying(StripeConnectException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.MERCHANT_ALREADY_LINKED);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    /**
     * If reading the account back fails after the code was spent, nothing is stored and the state stays spent: the
     * farm starts again rather than a retry reusing a code Stripe has already consumed.
     */
    @Test
    void completeStoresNothingWhenTheAccountReadFailsAfterTheExchange() {
        stateIsValidFor("st1", MERCHANT);
        when(client.exchangeOAuthCode("code1")).thenReturn(ACCOUNT);
        when(client.retrieveAccount(ACCOUNT)).thenThrow(StripeConnectException.apiError("down", null));

        assertThatThrownBy(() -> service.completeOAuth("code1", "st1"))
                .isInstanceOfSatisfying(StripeConnectException.class,
                        e -> assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.STRIPE_API_ERROR));
        verify(accounts, never()).save(any());
        verify(client, org.mockito.Mockito.times(1)).exchangeOAuthCode(anyString());
    }

    /** With OAuth unconfigured, completing is refused too: nothing was ever started. */
    @Test
    void completeRefusedWhenOAuthIsNotConfigured() {
        props.getOauth().setRedirectUri("");

        assertThatThrownBy(() -> service.completeOAuth("code1", "st1"))
                .isInstanceOfSatisfying(StripeConnectException.class,
                        e -> assertThat(e.getCode()).isEqualTo(StripeConnectExceptionCode.OAUTH_DISABLED));
        verify(states, never()).consume(anyString(), any());
    }
}
