package xyz.tcheeric.payment.adapter.paypal.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectException;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification;

class PaypalPaymentReferenceVerifierTest {

    static final String STALL = "npub-stall";
    static final String SELLER = "SELLER123";
    static final String CAPTURE = "3C679366HH908993F";

    ConnectedPaypalAccountRepository accounts;
    StripePurchaseRepository purchases;
    PaypalOrdersClient paypal;
    PaypalPaymentReferenceVerifier verifier;
    StripePurchase row;

    @BeforeEach
    void setUp() {
        accounts = mock(ConnectedPaypalAccountRepository.class);
        purchases = mock(StripePurchaseRepository.class);
        paypal = mock(PaypalOrdersClient.class);
        verifier = new PaypalPaymentReferenceVerifier(accounts, purchases, paypal, false);
        ConnectedPaypalAccount account = new ConnectedPaypalAccount();
        account.setMerchantPubkey(STALL);
        account.setPaypalMerchantId(SELLER);
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.of(account));
        row = new StripePurchase();
        row.setProcessor(StripePurchase.PROCESSOR_PAYPAL);
        row.setPaymentIntentId(CAPTURE);
        row.setConnectedAccountId(SELLER);
        row.setAmountMinor(1250L);
        row.setCurrency("usd");
        row.setLivemode(false);
        when(purchases.findByPaymentIntentId(CAPTURE)).thenReturn(Optional.of(row));
    }

    @Test
    void aCaptureMatchingItsPurchaseIsVerified() {
        when(paypal.getCapture(SELLER, CAPTURE)).thenReturn(capture("COMPLETED", 1250L, SELLER));

        PaymentReferenceVerification answer = verifier.verify(STALL, CAPTURE);

        assertThat(answer.result()).isEqualTo(PaymentReferenceVerification.VERIFIED);
        assertThat(answer.amountMinor()).isEqualTo(1250L);
        assertThat(answer.livemode()).isFalse();
    }

    @Test
    void aMalformedReferenceIsNeverSentToPaypal() {
        assertThat(verifier.verify(STALL, "pi_123").result()).isEqualTo(PaymentReferenceVerification.NOT_FOUND);
        verify(paypal, never()).getCapture(anyString(), anyString());
    }

    @Test
    void aStallWithoutPaypalIsNotConnected() {
        assertThat(verifier.verify("npub-other", CAPTURE).result())
                .isEqualTo(PaymentReferenceVerification.NOT_CONNECTED);
    }

    @Test
    void anotherSellersCaptureIsNotFound() {
        when(paypal.getCapture(SELLER, CAPTURE)).thenReturn(capture("COMPLETED", 1250L, "SOMEONE"));
        assertThat(verifier.verify(STALL, CAPTURE).result()).isEqualTo(PaymentReferenceVerification.NOT_FOUND);

        when(paypal.getCapture(SELLER, "4C679366HH908993F"))
                .thenThrow(new PaypalApiException("no", 404, "RESOURCE_NOT_FOUND", null, null));
        assertThat(verifier.verify(STALL, "4C679366HH908993F").result())
                .isEqualTo(PaymentReferenceVerification.NOT_FOUND);
    }

    @Test
    void aPendingCaptureIsNotSucceededAndIsNotCached() {
        when(paypal.getCapture(SELLER, CAPTURE)).thenReturn(capture("PENDING", 1250L, SELLER));

        assertThat(verifier.verify(STALL, CAPTURE).result()).isEqualTo(PaymentReferenceVerification.NOT_SUCCEEDED);
        verifier.verify(STALL, CAPTURE);
        verify(paypal, times(2)).getCapture(SELLER, CAPTURE);
    }

    @Test
    void aSettledCaptureIsReadOnce() {
        when(paypal.getCapture(SELLER, CAPTURE)).thenReturn(capture("COMPLETED", 1250L, SELLER));

        verifier.verify(STALL, CAPTURE);
        verifier.verify(STALL, CAPTURE);
        verify(paypal, times(1)).getCapture(SELLER, CAPTURE);
    }

    @Test
    void theLedgerNotTheBrowserDecidesAmountAndMode() {
        when(paypal.getCapture(SELLER, CAPTURE)).thenReturn(capture("COMPLETED", 999L, SELLER));
        assertThat(verifier.verify(STALL, CAPTURE).result()).isEqualTo(PaymentReferenceVerification.MISMATCH);

        PaypalPaymentReferenceVerifier live = new PaypalPaymentReferenceVerifier(accounts, purchases, paypal, true);
        when(paypal.getCapture(SELLER, CAPTURE)).thenReturn(capture("COMPLETED", 1250L, SELLER));
        assertThat(live.verify(STALL, CAPTURE).detail()).contains("test mode");
    }

    @Test
    void aStripeRowNamingTheSameIdIsNotThisPurchase() {
        row.setProcessor(StripePurchase.PROCESSOR_STRIPE);
        when(paypal.getCapture(SELLER, CAPTURE)).thenReturn(capture("COMPLETED", 1250L, SELLER));
        assertThat(verifier.verify(STALL, CAPTURE).result()).isEqualTo(PaymentReferenceVerification.MISMATCH);
    }

    @Test
    void refundsAndDisputesComeFromTheLedgerRow() {
        when(paypal.getCapture(SELLER, CAPTURE)).thenReturn(capture("PARTIALLY_REFUNDED", 1250L, SELLER));
        row.setRefundedAmountMinor(250L);
        PaymentReferenceVerification refunded = verifier.verify(STALL, CAPTURE);
        assertThat(refunded.result()).isEqualTo(PaymentReferenceVerification.REFUNDED);
        assertThat(refunded.refundedMinor()).isEqualTo(250L);

        row.setDisputed(true);
        assertThat(verifier.verify(STALL, CAPTURE).result()).isEqualTo(PaymentReferenceVerification.DISPUTED);
    }

    @Test
    void anOutageIsUnknownNotAnAnswer() {
        when(paypal.getCapture(SELLER, CAPTURE)).thenThrow(new PaypalApiException("down", 503, null, null, null));
        assertThatThrownBy(() -> verifier.verify(STALL, CAPTURE)).isInstanceOf(PaypalConnectException.class);
    }

    static PaypalCapture capture(String status, Long amount, String payee) {
        return new PaypalCapture(CAPTURE, status, amount, "usd", "test:q-1", "ORDER-1", payee);
    }
}
