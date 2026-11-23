package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalCheckoutCreationException;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalValidationException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrderRequest;

class PaypalCheckoutServiceTest {

    PaypalGatewayProperties properties;
    PaypalOrdersClient orders;
    PaypalCheckoutService service;

    @BeforeEach
    void setUp() {
        properties = new PaypalGatewayProperties();
        properties.setSuccessUrl("https://wallet.example/buy/done?order={ORDER_ID}");
        properties.setCancelUrl("https://wallet.example/buy/cancelled");
        properties.setEnvironment("prod");
        properties.setAllowedCurrencies(List.of("usd", "eur"));
        orders = mock(PaypalOrdersClient.class);
        service = new PaypalCheckoutService(properties, orders);
        when(orders.createOrder(any())).thenReturn(order("ORDER-1", "https://paypal.example/approve"));
    }

    @Test
    void anOrderIsStampedWithTheEnvironmentAndIdempotentPerQuote() {
        service.createPurchaseOrder("q-1", 1250, "Voucher", "USD", " SELLER ", null, null, null);

        PaypalOrderRequest request = sent();
        assertThat(request.getCustomId()).isEqualTo("prod:q-1");
        assertThat(request.getRequestId()).isEqualTo("paypal:order:q-1");
        assertThat(request.getMerchantId()).isEqualTo("SELLER");
        assertThat(request.getCurrency()).isEqualTo("usd");
        assertThat(PaypalCheckoutService.captureRequestId("ORDER-1")).isEqualTo("paypal:capture:ORDER-1");
    }

    @Test
    void withoutAReturnRoutePaypalReturnsStraightToTheSuccessPageWithoutThePlaceholder() {
        service.createPurchaseOrder("q-1", 1250, "Voucher", "usd", "SELLER", null, null, null);

        assertThat(sent().getReturnUrl()).isEqualTo("https://wallet.example/buy/done?order=");
        assertThat(sent().getCancelUrl()).isEqualTo("https://wallet.example/buy/cancelled");
    }

    @Test
    void withAReturnRoutePaypalReturnsHereToCapture() {
        properties.setReturnBaseUrl("https://pay.example/");

        service.createPurchaseOrder("q-1", 1250, "Voucher", "usd", "SELLER", null, null, null);

        assertThat(sent().getReturnUrl()).isEqualTo("https://pay.example/api/v1/checkout/paypal/return");
    }

    @Test
    void refusalsHappenBeforePaypalIsCalled() {
        assertThatThrownBy(() -> service.createPurchaseOrder("q", 0, "v", "usd", "S", null, null, null))
                .isInstanceOf(PaypalValidationException.class);
        assertThatThrownBy(() -> service.createPurchaseOrder("q", 100, "v", "usd", " ", null, null, null))
                .isInstanceOf(PaypalValidationException.class);
        assertThatThrownBy(() -> service.createPurchaseOrder("q", 100, "v", "usd", "S", 100L, null, null))
                .isInstanceOf(PaypalValidationException.class);
        assertThatThrownBy(() -> service.createPurchaseOrder("q", 100, "v", "gbp", "S", null, null, null))
                .isInstanceOf(PaypalValidationException.class);
        verify(orders, never()).createOrder(any());
    }

    @Test
    void anOutageIsToldFromARefusal() {
        when(orders.createOrder(any())).thenThrow(new PaypalApiException("down", 503, null, null, null));
        assertThatThrownBy(() -> service.createPurchaseOrder("q", 100, "v", "usd", "S", null, null, null))
                .isInstanceOfSatisfying(PaypalCheckoutCreationException.class,
                        e -> assertThat(e.isPaypalUnavailable()).isTrue());

        org.mockito.Mockito.doThrow(new PaypalApiException("no", 422, "PAYEE_ACCOUNT_RESTRICTED", null, null))
                .when(orders).createOrder(any());
        assertThatThrownBy(() -> service.createPurchaseOrder("q", 100, "v", "usd", "S", null, null, null))
                .isInstanceOfSatisfying(PaypalCheckoutCreationException.class,
                        e -> assertThat(e.isPaypalUnavailable()).isFalse());
    }

    @Test
    void anOrderWithoutAnApprovalLinkIsAFailure() {
        when(orders.createOrder(any())).thenReturn(order("ORDER-1", null));
        assertThatThrownBy(() -> service.createPurchaseOrder("q", 100, "v", "usd", "S", null, null, null))
                .isInstanceOf(PaypalCheckoutCreationException.class);
    }

    @Test
    void longDescriptionsFitPaypalsLimit() {
        service.createPurchaseOrder("q", 100, "x".repeat(300), "usd", "S", null, null, null);
        assertThat(sent().getDescription()).hasSize(127).endsWith("...");
    }

    private PaypalOrderRequest sent() {
        ArgumentCaptor<PaypalOrderRequest> captor = ArgumentCaptor.forClass(PaypalOrderRequest.class);
        verify(orders, org.mockito.Mockito.atLeastOnce()).createOrder(captor.capture());
        return captor.getValue();
    }

    private static PaypalOrder order(String id, String approveUrl) {
        return new PaypalOrder(id, "PAYER_ACTION_REQUIRED", approveUrl, null, null, 100L, "usd", List.of(), 0);
    }
}
