package xyz.tcheeric.payment.adapter.stripe.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripePlanCheckoutRequest;

/**
 * What the plan session asks Stripe for, through the real client method (not a copy of it), so a change to the
 * request fails here and not in front of a buyer.
 */
class StripePlanCheckoutParamsTest {

    private static Map<String, Object> params() {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setCheckoutExpirySeconds(900);
        StripePlanCheckoutRequest request = StripePlanCheckoutRequest.builder()
                .connectedAccountId("acct_farm").planId("plan-1").amountMinor(5000).currency("gbp")
                .description("Veg box").metadata(Map.of("plan_id", "plan-1", "buyer_pubkey", "ab"))
                .successUrl("https://w/ok").cancelUrl("https://w/no").idempotencyKey("stripe:plan:plan-1").build();
        return new StripeSdkCheckoutClient(properties).planCheckoutParams(request).toMap();
    }

    // The session is a subscription, priced monthly, with the instalment as the unit amount.
    @SuppressWarnings("unchecked")
    @Test
    void isAMonthlySubscriptionForOneInstalment() {
        Map<String, Object> params = params();

        assertThat(params.get("mode")).isEqualTo("subscription");
        Map<String, Object> item = ((List<Map<String, Object>>) params.get("line_items")).get(0);
        Map<String, Object> price = (Map<String, Object>) item.get("price_data");
        assertThat(price.get("unit_amount")).isEqualTo(5000L);
        assertThat(price.get("currency")).isEqualTo("gbp");
        assertThat(((Map<String, Object>) price.get("recurring")).get("interval")).isEqualTo("month");
    }

    // The plan id reaches the SUBSCRIPTION, not only the session: renewals have no Checkout Session, so the
    // subscription's metadata is the only thing that can name the plan on every later invoice.
    @SuppressWarnings("unchecked")
    @Test
    void theSubscriptionCarriesThePlanId() {
        Map<String, Object> params = params();

        assertThat(((Map<String, Object>) params.get("metadata")).get("plan_id")).isEqualTo("plan-1");
        Map<String, Object> subscription = (Map<String, Object>) params.get("subscription_data");
        assertThat(((Map<String, Object>) subscription.get("metadata")).get("plan_id")).isEqualTo("plan-1");
    }

    // Neither payment_intent_data nor an application fee is valid in subscription mode, and Imani takes no cut.
    @Test
    void asksForNoPaymentIntentDataAndNoApplicationFee() {
        Map<String, Object> params = params();

        assertThat(params).doesNotContainKey("payment_intent_data");
        assertThat(params.toString()).doesNotContain("application_fee");
    }
}
