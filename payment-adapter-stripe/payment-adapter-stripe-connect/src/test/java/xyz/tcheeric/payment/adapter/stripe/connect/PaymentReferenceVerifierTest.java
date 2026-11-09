package xyz.tcheeric.payment.adapter.stripe.connect;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentReferenceVerifierTest {

    private static final String STALL = "a".repeat(64);
    private static final String ACCOUNT = "acct_stall";
    private static final String PI = "pi_3PqRsTuVwXyZ";

    private final ConnectedStripeAccountRepository accounts = mock(ConnectedStripeAccountRepository.class);
    private final StripePurchaseRepository purchases = mock(StripePurchaseRepository.class);
    private final StripeConnectClient stripe = mock(StripeConnectClient.class);
    private PaymentReferenceVerifier verifier;

    @BeforeEach
    void setUp() {
        ConnectedStripeAccount account = new ConnectedStripeAccount();
        account.setMerchantPubkey(STALL);
        account.setStripeAccountId(ACCOUNT);
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.of(account));
        verifier = new PaymentReferenceVerifier(accounts, purchases, stripe);
    }

    private void stripeSays(String status, long amount, String currency) {
        when(stripe.retrievePaymentIntent(ACCOUNT, PI))
                .thenReturn(Optional.of(new PaymentIntentSnapshot(PI, status, amount, currency, false)));
    }

    private void purchaseRow(String account, long amount, String currency) {
        StripePurchase row = new StripePurchase();
        row.setPaymentIntentId(PI);
        row.setConnectedAccountId(account);
        row.setAmountMinor(amount);
        row.setCurrency(currency);
        when(purchases.findByPaymentIntentId(PI)).thenReturn(Optional.of(row));
    }

    @Test
    void succeededOnTheStallsAccountAndMatchingTheRowIsVerified() {
        stripeSays("succeeded", 1500, "usd");
        purchaseRow(ACCOUNT, 1500, "USD");

        var answer = verifier.verify(STALL, PI);

        assertThat(answer.result()).isEqualTo("verified");
        assertThat(answer.amountMinor()).isEqualTo(1500);
        assertThat(answer.currency()).isEqualTo("usd");
    }

    @Test
    void readsWithTheStallsOwnAccountNeverAnotherOne() {
        stripeSays("succeeded", 1500, "usd");
        purchaseRow(ACCOUNT, 1500, "usd");
        verifier.verify(STALL, PI);
        verify(stripe).retrievePaymentIntent(ACCOUNT, PI);
    }

    @Test
    void absentFromTheStallsAccountIsNotFound() {
        when(stripe.retrievePaymentIntent(ACCOUNT, PI)).thenReturn(Optional.empty());
        assertThat(verifier.verify(STALL, PI).result()).isEqualTo("not_found");
    }

    @Test
    void amountDifferentFromThePurchaseIsAMismatch() {
        stripeSays("succeeded", 1500, "usd");
        purchaseRow(ACCOUNT, 9900, "usd");
        assertThat(verifier.verify(STALL, PI).result()).isEqualTo("mismatch");
    }

    @Test
    void currencyDifferentFromThePurchaseIsAMismatch() {
        stripeSays("succeeded", 1500, "eur");
        purchaseRow(ACCOUNT, 1500, "usd");
        assertThat(verifier.verify(STALL, PI).result()).isEqualTo("mismatch");
    }

    @Test
    void purchaseRecordedForAnotherAccountIsAMismatch() {
        stripeSays("succeeded", 1500, "usd");
        purchaseRow("acct_other", 1500, "usd");
        assertThat(verifier.verify(STALL, PI).result()).isEqualTo("mismatch");
    }

    @Test
    void aPaymentNoPurchaseNamesIsAMismatch() {
        stripeSays("succeeded", 1500, "usd");
        when(purchases.findByPaymentIntentId(PI)).thenReturn(Optional.empty());
        assertThat(verifier.verify(STALL, PI).result()).isEqualTo("mismatch");
    }

    @Test
    void notYetSucceededIsNotVerifiedAndNotCached() {
        stripeSays("processing", 1500, "usd");
        assertThat(verifier.verify(STALL, PI).result()).isEqualTo("not_succeeded");
        verifier.verify(STALL, PI);
        verify(stripe, times(2)).retrievePaymentIntent(ACCOUNT, PI);
    }

    @Test
    void aVerdictIsCachedBecauseASucceededPaymentDoesNotChange() {
        stripeSays("succeeded", 1500, "usd");
        purchaseRow(ACCOUNT, 1500, "usd");
        verifier.verify(STALL, PI);
        verifier.verify(STALL, PI);
        verify(stripe, times(1)).retrievePaymentIntent(ACCOUNT, PI);
    }

    @Test
    void aStallWithoutAConnectedAccountIsNotConnected() {
        when(accounts.findByMerchantPubkey("b".repeat(64))).thenReturn(Optional.empty());
        assertThat(verifier.verify("b".repeat(64), PI).result()).isEqualTo("not_connected");
        verify(stripe, never()).retrievePaymentIntent(anyString(), anyString());
    }

    @Test
    void somethingThatIsNotAPaymentIntentIdNeverReachesStripe() {
        assertThat(verifier.verify(STALL, "ch_123").result()).isEqualTo("not_found");
        assertThat(verifier.verify(STALL, "pi_../../x").result()).isEqualTo("not_found");
        verify(stripe, never()).retrievePaymentIntent(anyString(), anyString());
    }

    @Test
    void unreachableStripeIsAnErrorNotAVerdictAndIsNotCached() {
        when(stripe.retrievePaymentIntent(ACCOUNT, PI))
                .thenThrow(StripeConnectException.apiError("down", null));
        assertThatThrownBy(() -> verifier.verify(STALL, PI)).isInstanceOf(StripeConnectException.class);
        assertThatThrownBy(() -> verifier.verify(STALL, PI)).isInstanceOf(StripeConnectException.class);
        verify(stripe, times(2)).retrievePaymentIntent(ACCOUNT, PI);
    }
}
