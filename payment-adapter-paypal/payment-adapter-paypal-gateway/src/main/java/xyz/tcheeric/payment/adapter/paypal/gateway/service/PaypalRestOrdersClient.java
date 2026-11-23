package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalDispute;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalMoney;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrderRequest;

/** {@link PaypalOrdersClient} over PayPal's REST API, through {@link PaypalHttp}. */
public class PaypalRestOrdersClient implements PaypalOrdersClient {

    private final PaypalHttp http;

    public PaypalRestOrdersClient(PaypalHttp http) {
        this.http = http;
    }

    @Override
    public PaypalOrder createOrder(PaypalOrderRequest request) {
        String currency = PaypalMoney.code(request.getCurrency());
        Map<String, Object> amount = Map.of(
                "currency_code", currency,
                "value", PaypalMoney.toValue(request.getAmountMinor(), currency));

        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("reference_id", request.getQuoteId());
        unit.put("custom_id", request.getCustomId());
        unit.put("description", request.getDescription());
        unit.put("amount", amount);
        // The stall is merchant of record (ADR 0001): the money is the seller's from the first moment.
        unit.put("payee", Map.of("merchant_id", request.getMerchantId()));
        if (request.getPlatformFeeMinor() != null && request.getPlatformFeeMinor() > 0) {
            unit.put("payment_instruction", Map.of("platform_fees", List.of(Map.of("amount", Map.of(
                    "currency_code", currency,
                    "value", PaypalMoney.toValue(request.getPlatformFeeMinor(), currency))))));
        }

        Map<String, Object> experience = new LinkedHashMap<>();
        experience.put("return_url", request.getReturnUrl());
        experience.put("cancel_url", request.getCancelUrl());
        // The card form first: a buyer without a PayPal account pays as a guest (ADR 0002).
        experience.put("landing_page", "GUEST_CHECKOUT");
        experience.put("shipping_preference", "NO_SHIPPING");
        experience.put("user_action", "PAY_NOW");
        if (request.getBrandName() != null && !request.getBrandName().isBlank()) {
            experience.put("brand_name", request.getBrandName().trim());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intent", "CAPTURE");
        body.put("purchase_units", List.of(unit));
        body.put("payment_source", Map.of("paypal", Map.of("experience_context", experience)));

        JsonNode order = http.post("/v2/checkout/orders", body)
                .asSeller(request.getMerchantId())
                .requestId(request.getRequestId())
                .send();
        return PaypalOrder.from(order);
    }

    @Override
    public PaypalOrder getOrder(String merchantId, String orderId) {
        return PaypalOrder.from(http.get("/v2/checkout/orders/" + PaypalHttp.segment(orderId))
                .asSeller(merchantId)
                .send());
    }

    @Override
    public PaypalOrder captureOrder(String merchantId, String orderId, String requestId) {
        return PaypalOrder.from(http.post("/v2/checkout/orders/" + PaypalHttp.segment(orderId) + "/capture", "{}")
                .asSeller(merchantId)
                .requestId(requestId)
                .send());
    }

    @Override
    public PaypalCapture getCapture(String merchantId, String captureId) {
        return PaypalCapture.from(http.get("/v2/payments/captures/" + PaypalHttp.segment(captureId))
                .asSeller(merchantId)
                .send(), null);
    }

    @Override
    public List<PaypalDispute> disputesFor(String merchantId, String captureId) {
        JsonNode answer = http.get("/v1/customer/disputes")
                .asSeller(merchantId)
                .query("disputed_transaction_id", captureId)
                .send();
        List<PaypalDispute> disputes = new ArrayList<>();
        for (JsonNode item : answer.path("items")) {
            String id = item.path("dispute_id").asText(null);
            if (id != null) {
                disputes.add(new PaypalDispute(id, item.path("status").asText(null)));
            }
        }
        return List.copyOf(disputes);
    }
}
