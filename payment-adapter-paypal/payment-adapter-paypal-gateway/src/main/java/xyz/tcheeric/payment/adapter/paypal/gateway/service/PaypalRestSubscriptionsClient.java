package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalMoney;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSubscription;

/**
 * {@link PaypalSubscriptionsClient} over PayPal's REST API, through {@link PaypalHttp} so every call uses the
 * platform's credentials, acts as the seller and is retried only when PayPal could not answer.
 *
 * <p>A product and a billing plan are created per instalment plan. PayPal fixes the price and the schedule on the
 * billing plan, and each buyer picks their own amount and term, so plans cannot be shared. The product exists only
 * because PayPal requires a plan to name one.
 */
@Slf4j
public class PaypalRestSubscriptionsClient implements PaypalSubscriptionsClient {

    /** PayPal's answer when a subscription is already cancelled or expired: the outcome asked for already holds. */
    static final String ALREADY_ENDED = "SUBSCRIPTION_STATUS_INVALID";

    private final PaypalHttp http;

    public PaypalRestSubscriptionsClient(PaypalHttp http) {
        this.http = http;
    }

    @Override
    public PaypalSubscription startSubscription(PaypalSubscriptionRequest request) {
        String productId = createProduct(request);
        String billingPlanId = createBillingPlan(request, productId);
        return createSubscription(request, billingPlanId);
    }

    private String createProduct(PaypalSubscriptionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", request.description());
        body.put("type", "SERVICE");
        JsonNode product = http.post("/v1/catalogs/products", body)
                .asSeller(request.merchantId())
                .requestId("imani-plan-product:" + request.planId())
                .send();
        return requireId(product, "product");
    }

    private String createBillingPlan(PaypalSubscriptionRequest request, String productId) {
        String currency = PaypalMoney.code(request.currency());
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("frequency", Map.of("interval_unit", request.intervalUnit(), "interval_count", 1));
        cycle.put("tenure_type", "REGULAR");
        cycle.put("sequence", 1);
        cycle.put("total_cycles", request.totalCycles());
        cycle.put("pricing_scheme", Map.of("fixed_price", Map.of(
                "currency_code", currency, "value", PaypalMoney.toValue(request.amountMinor(), currency))));

        Map<String, Object> preferences = new LinkedHashMap<>();
        // A missed instalment is not billed again later: paid means owed, and the buyer gets vouchers only for what
        // PayPal actually took on its schedule.
        preferences.put("auto_bill_outstanding", false);
        preferences.put("payment_failure_threshold", 3);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product_id", productId);
        body.put("name", request.description());
        body.put("status", "ACTIVE");
        body.put("billing_cycles", List.of(cycle));
        body.put("payment_preferences", preferences);
        JsonNode plan = http.post("/v1/billing/plans", body)
                .asSeller(request.merchantId())
                .requestId("imani-plan-billing:" + request.planId())
                .send();
        return requireId(plan, "billing plan");
    }

    private PaypalSubscription createSubscription(PaypalSubscriptionRequest request, String billingPlanId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("shipping_preference", "NO_SHIPPING");
        context.put("user_action", "SUBSCRIBE_NOW");
        context.put("return_url", request.returnUrl());
        context.put("cancel_url", request.cancelUrl());
        if (request.brandName() != null && !request.brandName().isBlank()) {
            context.put("brand_name", request.brandName().trim());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan_id", billingPlanId);
        body.put("custom_id", request.customId());
        body.put("application_context", context);
        JsonNode subscription = http.post("/v1/billing/subscriptions", body)
                .asSeller(request.merchantId())
                .requestId("imani-plan-subscription:" + request.planId())
                .send();
        PaypalSubscription created = PaypalSubscription.from(subscription);
        if (created.id() == null) {
            throw new PaypalApiException("PayPal created a subscription but returned no id", 0, null, null, null);
        }
        return created;
    }

    @Override
    public PaypalSubscription getSubscription(String merchantId, String subscriptionId) {
        return PaypalSubscription.from(http.get("/v1/billing/subscriptions/" + PaypalHttp.segment(subscriptionId))
                .asSeller(merchantId)
                .send());
    }

    @Override
    public void cancelSubscription(String merchantId, String subscriptionId, String reason) {
        try {
            http.post("/v1/billing/subscriptions/" + PaypalHttp.segment(subscriptionId) + "/cancel",
                            Map.of("reason", reason == null || reason.isBlank() ? "Cancelled" : reason))
                    .asSeller(merchantId)
                    .send();
        } catch (PaypalApiException e) {
            if (e.status() == 422 && e.hasIssue(ALREADY_ENDED)) {
                log.info("PayPal subscription {} had already ended; cancel is a no-op", subscriptionId);
                return;
            }
            throw e;
        }
    }

    @Override
    public List<PaypalSale> transactions(String merchantId, String subscriptionId, Instant from, Instant to) {
        JsonNode answer = http.get("/v1/billing/subscriptions/" + PaypalHttp.segment(subscriptionId) + "/transactions")
                .asSeller(merchantId)
                .query("start_time", from.truncatedTo(ChronoUnit.SECONDS).toString())
                .query("end_time", to.truncatedTo(ChronoUnit.SECONDS).toString())
                .send();
        List<PaypalSale> sales = new ArrayList<>();
        for (JsonNode transaction : answer.path("transactions")) {
            PaypalSale sale = PaypalSale.fromTransaction(transaction, subscriptionId);
            if (sale.id() != null) {
                sales.add(sale);
            }
        }
        return List.copyOf(sales);
    }

    @Override
    public PaypalSale getSale(String merchantId, String saleId) {
        return PaypalSale.fromSale(http.get("/v1/payments/sale/" + PaypalHttp.segment(saleId))
                .asSeller(merchantId)
                .send());
    }

    private static String requireId(JsonNode answer, String what) {
        String id = answer.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new PaypalApiException("PayPal created a " + what + " but returned no id", 0, null, null, null);
        }
        return id;
    }
}
