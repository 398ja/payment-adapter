package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalValidationException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSubscription;

/** The Subscriptions client against a local PayPal stand-in. */
class PaypalRestSubscriptionsClientTest {

    static final String MERCHANT = "SELLER123";

    WireMockServer paypal;
    PaypalRestSubscriptionsClient client;

    @BeforeEach
    void setUp() {
        paypal = new WireMockServer(0);
        paypal.start();
        PaypalGatewayProperties properties = new PaypalGatewayProperties();
        properties.setClientId("platform-client");
        properties.setClientSecret("platform-secret");
        properties.setApiBase("http://localhost:" + paypal.port());
        properties.setMaxNetworkRetries(0);
        client = new PaypalRestSubscriptionsClient(new PaypalHttp(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), Clock.systemUTC()));
        paypal.stubFor(post("/v1/oauth2/token").willReturn(okJson(
                "{\"access_token\":\"TOKEN-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    @AfterEach
    void tearDown() {
        paypal.stop();
    }

    static PaypalSubscriptionRequest request(Integer instalments) {
        return new PaypalSubscriptionRequest("plan-1", MERCHANT, 5000, "gbp", "month", instalments, "test:plan-1",
                "Instalment plan", "Imani", "https://wallet.example/paid", "https://wallet.example/cancel");
    }

    // Product, billing plan and subscription are created as the seller, idempotently, with a fixed term of N cycles.
    @Test
    void startingAPlanCreatesEverythingOnTheSellersAccount() {
        paypal.stubFor(post("/v1/catalogs/products").willReturn(okJson("{\"id\":\"PROD-1\"}")));
        paypal.stubFor(post("/v1/billing/plans").willReturn(okJson("{\"id\":\"P-1\",\"status\":\"ACTIVE\"}")));
        paypal.stubFor(post("/v1/billing/subscriptions").willReturn(okJson("{\"id\":\"I-1\",\"status\":\"APPROVAL_PENDING\","
                + "\"links\":[{\"rel\":\"approve\",\"href\":\"https://paypal.example/approve?ba=1\"}]}")));

        PaypalSubscription subscription = client.startSubscription(request(6));

        assertThat(subscription.id()).isEqualTo("I-1");
        assertThat(subscription.approveUrl()).isEqualTo("https://paypal.example/approve?ba=1");
        assertThat(subscription.awaitingApproval()).isTrue();
        paypal.verify(postRequestedFor(urlEqualTo("/v1/catalogs/products"))
                .withHeader("PayPal-Request-Id", equalTo("imani-plan-product:plan-1")));
        paypal.verify(postRequestedFor(urlEqualTo("/v1/billing/plans"))
                .withHeader("PayPal-Request-Id", equalTo("imani-plan-billing:plan-1"))
                .withRequestBody(matchingJsonPath("$.product_id", equalTo("PROD-1")))
                .withRequestBody(matchingJsonPath("$.billing_cycles[0].total_cycles", equalTo("6")))
                .withRequestBody(matchingJsonPath("$.billing_cycles[0].frequency.interval_unit", equalTo("MONTH")))
                .withRequestBody(matchingJsonPath("$.billing_cycles[0].pricing_scheme.fixed_price.value", equalTo("50.00")))
                .withRequestBody(matchingJsonPath("$.billing_cycles[0].pricing_scheme.fixed_price.currency_code",
                        equalTo("GBP"))));
        paypal.verify(postRequestedFor(urlEqualTo("/v1/billing/subscriptions"))
                .withHeader("PayPal-Request-Id", equalTo("imani-plan-subscription:plan-1"))
                .withRequestBody(matchingJsonPath("$.plan_id", equalTo("P-1")))
                .withRequestBody(matchingJsonPath("$.custom_id", equalTo("test:plan-1"))));
        assertThat(paypal.findAll(postRequestedFor(urlEqualTo("/v1/billing/subscriptions"))).get(0)
                .getHeader("PayPal-Auth-Assertion")).isNotBlank();
    }

    // An open-ended plan is sent as zero cycles, PayPal's "until cancelled".
    @Test
    void anOpenEndedPlanHasNoCycleLimit() {
        assertThat(request(null).totalCycles()).isZero();
    }

    // A request with no seller is refused before anything is sent.
    @Test
    void aPlanWithoutASellerIsRefused() {
        assertThatThrownBy(() -> new PaypalSubscriptionRequest("plan-1", " ", 5000, "gbp", "month", 6, "x", "d", null,
                null, null)).isInstanceOf(PaypalValidationException.class);
    }

    // Cancelling a subscription that already ended is not an error.
    @Test
    void cancellingAnEndedSubscriptionIsANoOp() {
        paypal.stubFor(post("/v1/billing/subscriptions/I-1/cancel").willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"name\":\"UNPROCESSABLE_ENTITY\",\"details\":[{\"issue\":\"SUBSCRIPTION_STATUS_INVALID\"}]}")));

        client.cancelSubscription(MERCHANT, "I-1", "Cancelled by the buyer");
    }

    // Any other refusal to cancel is surfaced, so the buyer is told to try again.
    @Test
    void otherCancelRefusalsAreSurfaced() {
        paypal.stubFor(post("/v1/billing/subscriptions/I-1/cancel").willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json").withBody("{\"name\":\"NOT_AUTHORIZED\"}")));

        assertThatThrownBy(() -> client.cancelSubscription(MERCHANT, "I-1", null))
                .isInstanceOf(PaypalApiException.class);
    }

    // A subscription's transactions are read into sales with their amount, state and time.
    @Test
    void transactionsAreReadAsSales() {
        paypal.stubFor(get(urlPathEqualTo("/v1/billing/subscriptions/I-1/transactions")).willReturn(okJson(
                "{\"transactions\":[{\"id\":\"SALE-1\",\"status\":\"COMPLETED\",\"time\":\"2027-01-01T10:00:00Z\","
                        + "\"amount_with_breakdown\":{\"gross_amount\":{\"currency_code\":\"GBP\",\"value\":\"50.00\"}}}]}")));

        List<PaypalSale> sales = client.transactions(MERCHANT, "I-1", Instant.parse("2026-12-01T00:00:00Z"),
                Instant.parse("2027-01-02T00:00:00Z"));

        assertThat(sales).containsExactly(new PaypalSale("SALE-1", "completed", 5000L, "gbp", "I-1",
                Instant.parse("2027-01-01T10:00:00Z")));
    }
}
