package xyz.tcheeric.payment.adapter.paypal.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.PaypalCheckout;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalCheckoutCreationException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalCheckoutService;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.Outcome;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseCheckoutController;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseCheckoutController.PurchaseRequest;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseCheckoutController.PurchaseResponse;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;

class PaypalPurchaseCheckoutControllerTest {

    static final String STALL = "a".repeat(64);
    static final String BUYER = "b".repeat(64);
    static final String ORDER = "5O190127TN364715T";

    ConnectedPaypalAccountRepository accounts;
    PaypalCheckoutRepository checkouts;
    PaypalCheckoutService checkout;
    PaypalCaptureService captures;
    StallReadinessClient readiness;
    PaypalGatewayProperties properties;
    PaypalPurchaseCheckoutController controller;
    ConnectedPaypalAccount account;

    @BeforeEach
    void setUp() {
        accounts = mock(ConnectedPaypalAccountRepository.class);
        checkouts = mock(PaypalCheckoutRepository.class);
        checkout = mock(PaypalCheckoutService.class);
        captures = mock(PaypalCaptureService.class);
        readiness = mock(StallReadinessClient.class);
        properties = new PaypalGatewayProperties();
        properties.setEnvironment("test");
        properties.setSuccessUrl("https://wallet.example/done?order={ORDER_ID}");
        properties.setCancelUrl("https://wallet.example/cancelled");
        when(checkout.properties()).thenReturn(properties);
        controller = new PaypalPurchaseCheckoutController(accounts, checkouts, checkout, captures, readiness,
                new ObjectMapper(), 1_000_000L);

        account = new ConnectedPaypalAccount();
        account.setMerchantPubkey(STALL);
        account.setPaypalMerchantId("SELLER123");
        account.setChargesEnabled(true);
        account.setDefaultCurrency("usd");
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.of(account));
        when(readiness.checkSale(eq(STALL), eq("usd"), anyLong())).thenReturn(StallReadinessClient.Readiness.ready());
        when(checkout.createPurchaseOrder(anyString(), anyLong(), anyString(), anyString(), anyString(), isNull(),
                isNull(), isNull())).thenReturn(new PaypalOrder(ORDER, "PAYER_ACTION_REQUIRED",
                "https://paypal.example/approve", null, null, 1250L, "usd", List.of(), 0));
    }

    @Test
    void aReadyStallGetsAnOrderAndTheRowIsWrittenFirst() throws Exception {
        ResponseEntity<?> response = controller.start(new PurchaseRequest(STALL.toUpperCase(), 1250L, BUYER, null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        PurchaseResponse body = (PurchaseResponse) response.getBody();
        assertThat(body.checkoutUrl()).isEqualTo("https://paypal.example/approve");
        ArgumentCaptor<PaypalCheckout> row = ArgumentCaptor.forClass(PaypalCheckout.class);
        verify(checkouts).save(row.capture());
        assertThat(row.getValue().getOrderId()).isEqualTo(ORDER);
        assertThat(row.getValue().getQuoteId()).isEqualTo(body.reference());
        assertThat(row.getValue().getStallPubkey()).isEqualTo(STALL);
        assertThat(row.getValue().getPaypalMerchantId()).isEqualTo("SELLER123");
        assertThat(row.getValue().getStatus()).isEqualTo(PaypalCheckout.Status.CREATED);
        Map<String, String> metadata = new ObjectMapper().readValue(row.getValue().getMetadata(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        assertThat(metadata).containsEntry("buyer_pubkey", BUYER).containsEntry("imani_env", "test");
    }

    @Test
    void theSameRefusalsAsStripe() {
        assertThat(status(new PurchaseRequest(null, 100L, BUYER, null))).isEqualTo(400);
        assertThat(status(new PurchaseRequest(STALL, 0L, BUYER, null))).isEqualTo(400);
        assertThat(status(new PurchaseRequest(STALL, 1_000_001L, BUYER, null))).isEqualTo(400);
        assertThat(status(new PurchaseRequest("not-a-stall", 100L, BUYER, null))).isEqualTo(400);
        assertThat(status(new PurchaseRequest(STALL, 100L, null, null))).isEqualTo(422);
        assertThat(status(new PurchaseRequest(STALL, 100L, BUYER, "00000000-0000-0000-0000-000000000000")))
                .isEqualTo(404);
        assertThat(status(new PurchaseRequest("c".repeat(64), 100L, BUYER, null))).isEqualTo(404);
        verify(checkout, never()).createPurchaseOrder(anyString(), anyLong(), anyString(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void aGuestIsToldToMakeAWalletInStripesWords() {
        ResponseEntity<?> response = controller.start(new PurchaseRequest(STALL, 100L, "npub1nope", null));
        assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo(PurchaseCheckoutController.GUEST_REFUSED);
    }

    @Test
    void aSellerWhoCannotTakeCardsYetIsAConflict() {
        account.setChargesEnabled(false);
        assertThat(status(new PurchaseRequest(STALL, 100L, BUYER, null))).isEqualTo(409);
    }

    @Test
    void notReadyIsAConflictAndUnknownIsRetryLater() {
        when(readiness.checkSale(eq(STALL), eq("usd"), anyLong()))
                .thenReturn(StallReadinessClient.Readiness.notReady(StallReadinessClient.NOT_ENROLLED));
        ResponseEntity<?> notReady = controller.start(new PurchaseRequest(STALL, 100L, BUYER, null));
        assertThat(notReady.getStatusCode().value()).isEqualTo(409);
        assertThat(((Map<?, ?>) notReady.getBody()).get("reason")).isEqualTo(StallReadinessClient.NOT_ENROLLED);

        when(readiness.checkSale(eq(STALL), eq("usd"), anyLong())).thenReturn(StallReadinessClient.Readiness.unknown());
        ResponseEntity<?> unknown = controller.start(new PurchaseRequest(STALL, 100L, BUYER, null));
        assertThat(unknown.getStatusCode().value()).isEqualTo(503);
        assertThat(unknown.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(PurchaseCheckoutController.RETRY_AFTER_SECONDS);
        verify(checkouts, never()).save(any());
    }

    @Test
    void aPaypalOutageIsRetryLaterAndARefusalIsABadRequest() {
        when(checkout.createPurchaseOrder(anyString(), anyLong(), anyString(), anyString(), anyString(), any(), any(),
                any())).thenThrow(new PaypalCheckoutCreationException("q", "usd",
                new PaypalApiException("down", 503, null, null, null)));
        ResponseEntity<?> outage = controller.start(new PurchaseRequest(STALL, 100L, BUYER, null));
        assertThat(outage.getStatusCode().value()).isEqualTo(503);
        assertThat(((Map<?, ?>) outage.getBody()).get("error"))
                .isEqualTo(PurchaseCheckoutController.CARD_PAYMENTS_UNAVAILABLE);

        org.mockito.Mockito.doThrow(new PaypalCheckoutCreationException("q", "usd",
                new PaypalApiException("no", 422, "PAYEE_ACCOUNT_RESTRICTED", null, null)))
                .when(checkout).createPurchaseOrder(anyString(), anyLong(), anyString(), anyString(), anyString(),
                        any(), any(), any());
        assertThat(status(new PurchaseRequest(STALL, 100L, BUYER, null))).isEqualTo(400);
        verify(checkouts, never()).save(any());
    }

    @Test
    void theBuyersReturnCapturesThenGoesToTheSuccessPage() {
        PaypalCheckout row = PaypalCaptureServiceTest.checkout();
        when(checkouts.findByOrderId(ORDER)).thenReturn(Optional.of(row));
        when(captures.capture(row, false)).thenReturn(Outcome.RECORDED);

        ResponseEntity<Void> response = controller.returned(ORDER);

        assertThat(response.getStatusCode().value()).isEqualTo(303);
        assertThat(response.getHeaders().getLocation()).hasToString("https://wallet.example/done?order=" + ORDER);
    }

    @Test
    void aFailedCaptureOnReturnStillLandsOnSuccessForTheWebhookToFinish() {
        PaypalCheckout row = PaypalCaptureServiceTest.checkout();
        when(checkouts.findByOrderId(ORDER)).thenReturn(Optional.of(row));
        when(captures.capture(row, false)).thenThrow(new IllegalStateException("db"));

        assertThat(controller.returned(ORDER).getHeaders().getLocation())
                .hasToString("https://wallet.example/done?order=" + ORDER);
    }

    @Test
    void aDeclinedCaptureOrAnUnknownOrderGoesToCancel() {
        PaypalCheckout row = PaypalCaptureServiceTest.checkout();
        when(checkouts.findByOrderId(ORDER)).thenReturn(Optional.of(row));
        when(captures.capture(row, false)).thenReturn(Outcome.DECLINED);

        assertThat(controller.returned(ORDER).getHeaders().getLocation()).hasToString("https://wallet.example/cancelled");
        assertThat(controller.returned("UNKNOWN1").getHeaders().getLocation())
                .hasToString("https://wallet.example/cancelled");
        assertThat(controller.returned("../evil").getHeaders().getLocation())
                .hasToString("https://wallet.example/cancelled");
        verify(captures, org.mockito.Mockito.times(1)).capture(any(), anyBoolean());
    }

    private int status(PurchaseRequest request) {
        return controller.start(request).getStatusCode().value();
    }
}
