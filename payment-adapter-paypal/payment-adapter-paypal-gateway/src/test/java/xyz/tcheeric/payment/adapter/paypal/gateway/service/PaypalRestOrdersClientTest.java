package xyz.tcheeric.payment.adapter.paypal.gateway.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalDispute;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrderRequest;

/** The REST transport and the Orders client against a local PayPal stand-in. */
class PaypalRestOrdersClientTest {

    static final String MERCHANT = "SELLER123";

    WireMockServer paypal;
    MutableClock clock;
    PaypalGatewayProperties properties;
    PaypalRestOrdersClient client;

    @BeforeEach
    void setUp() {
        paypal = new WireMockServer(0);
        paypal.start();
        properties = new PaypalGatewayProperties();
        properties.setClientId("platform-client");
        properties.setClientSecret("platform-secret");
        properties.setBnCode("IMANI_SP");
        properties.setApiBase("http://localhost:" + paypal.port());
        properties.setMaxNetworkRetries(1);
        clock = new MutableClock(Instant.parse("2027-01-01T00:00:00Z"));
        client = new PaypalRestOrdersClient(new PaypalHttp(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), clock));
        paypal.stubFor(post("/v1/oauth2/token").willReturn(okJson(
                "{\"access_token\":\"TOKEN-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    @AfterEach
    void tearDown() {
        paypal.stop();
    }

    @Test
    void createOrderActsForTheSellerAndNamesThemPayee() {
        paypal.stubFor(post("/v2/checkout/orders").willReturn(okJson(order("ORDER-1", "PAYER_ACTION_REQUIRED"))));

        PaypalOrder order = client.createOrder(PaypalOrderRequest.builder()
                .quoteId("q-1").amountMinor(1250).currency("usd").description("Coupon")
                .merchantId(MERCHANT).platformFeeMinor(50L).customId("prod:q-1")
                .returnUrl("https://pay.example/return").cancelUrl("https://wallet.example/cancel")
                .requestId("paypal:order:q-1").build());

        assertThat(order.id()).isEqualTo("ORDER-1");
        assertThat(order.approveUrl()).isEqualTo("https://www.sandbox.paypal.com/checkoutnow?token=ORDER-1");
        String assertion = paypal.findAll(postRequestedFor(urlEqualTo("/v2/checkout/orders"))).get(0)
                .getHeader("PayPal-Auth-Assertion");
        String claims = new String(Base64.getUrlDecoder().decode(assertion.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"iss\":\"platform-client\"").contains("\"payer_id\":\"" + MERCHANT + "\"");
        paypal.verify(postRequestedFor(urlEqualTo("/v2/checkout/orders"))
                .withHeader("Authorization", equalTo("Bearer TOKEN-1"))
                .withHeader("PayPal-Request-Id", equalTo("paypal:order:q-1"))
                .withHeader("PayPal-Partner-Attribution-Id", equalTo("IMANI_SP"))
                .withRequestBody(matchingJsonPath("$.intent", equalTo("CAPTURE")))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].payee.merchant_id", equalTo(MERCHANT)))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo("prod:q-1")))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].amount.value", equalTo("12.50")))
                .withRequestBody(matchingJsonPath(
                        "$.purchase_units[0].payment_instruction.platform_fees[0].amount.value", equalTo("0.50")))
                .withRequestBody(matchingJsonPath(
                        "$.payment_source.paypal.experience_context.landing_page", equalTo("GUEST_CHECKOUT"))));
    }

    @Test
    void theTokenIsReusedUntilAMinuteBeforeItExpires() {
        paypal.stubFor(get("/v2/checkout/orders/ORDER-1").willReturn(okJson(order("ORDER-1", "APPROVED"))));

        client.getOrder(MERCHANT, "ORDER-1");
        client.getOrder(MERCHANT, "ORDER-1");
        paypal.verify(1, postRequestedFor(urlEqualTo("/v1/oauth2/token")));

        clock.advance(Duration.ofSeconds(3600 - 59));
        client.getOrder(MERCHANT, "ORDER-1");
        paypal.verify(2, postRequestedFor(urlEqualTo("/v1/oauth2/token")));
    }

    @Test
    void aRevokedTokenIsReplacedOnce() {
        paypal.stubFor(get("/v2/checkout/orders/ORDER-1").inScenario("revoked")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid_token\"}"))
                .willSetStateTo("fresh"));
        paypal.stubFor(get("/v2/checkout/orders/ORDER-1").inScenario("revoked")
                .whenScenarioStateIs("fresh")
                .willReturn(okJson(order("ORDER-1", "APPROVED"))));

        assertThat(client.getOrder(MERCHANT, "ORDER-1").approved()).isTrue();
        paypal.verify(2, postRequestedFor(urlEqualTo("/v1/oauth2/token")));
    }

    @Test
    void aPersistent401IsNotRetriedForever() {
        paypal.stubFor(get("/v2/checkout/orders/ORDER-1")
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid_token\"}")));

        assertThatThrownBy(() -> client.getOrder(MERCHANT, "ORDER-1"))
                .isInstanceOfSatisfying(PaypalApiException.class, e -> assertThat(e.status()).isEqualTo(401));
        paypal.verify(2, getRequestedFor(urlEqualTo("/v2/checkout/orders/ORDER-1")));
    }

    @Test
    void anOutageIsRetriedWithTheSameRequestId() {
        paypal.stubFor(post("/v2/checkout/orders/ORDER-1/capture").inScenario("outage")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("up"));
        paypal.stubFor(post("/v2/checkout/orders/ORDER-1/capture").inScenario("outage")
                .whenScenarioStateIs("up")
                .willReturn(okJson(capturedOrder("ORDER-1", "CAPTURE-1"))));

        PaypalOrder order = client.captureOrder(MERCHANT, "ORDER-1", "paypal:capture:ORDER-1");

        assertThat(order.capture().id()).isEqualTo("CAPTURE-1");
        assertThat(order.capture().amountMinor()).isEqualTo(1250L);
        paypal.verify(2, postRequestedFor(urlEqualTo("/v2/checkout/orders/ORDER-1/capture"))
                .withHeader("PayPal-Request-Id", equalTo("paypal:capture:ORDER-1"))
                .withRequestBody(equalToJson("{}")));
    }

    @Test
    void aRefusalIsNotRetriedAndCarriesPaypalsIssue() {
        paypal.stubFor(post("/v2/checkout/orders/ORDER-1/capture").willReturn(aResponse().withStatus(422)
                .withHeader("Paypal-Debug-Id", "dbg-9")
                .withBody("{\"name\":\"UNPROCESSABLE_ENTITY\",\"details\":[{\"issue\":\"ORDER_ALREADY_CAPTURED\"}],"
                        + "\"message\":\"The requested action could not be performed\"}")));

        assertThatThrownBy(() -> client.captureOrder(MERCHANT, "ORDER-1", "paypal:capture:ORDER-1"))
                .isInstanceOfSatisfying(PaypalApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.hasIssue("ORDER_ALREADY_CAPTURED")).isTrue();
                    assertThat(e.debugId()).isEqualTo("dbg-9");
                    assertThat(e.isUnavailable()).isFalse();
                });
        paypal.verify(1, postRequestedFor(urlEqualTo("/v2/checkout/orders/ORDER-1/capture")));
    }

    @Test
    void retriesStopAtTheConfiguredCount() {
        paypal.stubFor(get("/v2/payments/captures/CAPTURE-1").willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.getCapture(MERCHANT, "CAPTURE-1"))
                .isInstanceOfSatisfying(PaypalApiException.class, e -> assertThat(e.isUnavailable()).isTrue());
        paypal.verify(2, getRequestedFor(urlEqualTo("/v2/payments/captures/CAPTURE-1")));
    }

    @Test
    void aMissingCaptureIsNotFound() {
        paypal.stubFor(get("/v2/payments/captures/CAPTURE-404").willReturn(aResponse().withStatus(404)
                .withBody("{\"name\":\"RESOURCE_NOT_FOUND\"}")));

        assertThatThrownBy(() -> client.getCapture(MERCHANT, "CAPTURE-404"))
                .isInstanceOfSatisfying(PaypalApiException.class, e -> assertThat(e.isNotFound()).isTrue());
    }

    @Test
    void captureAndDisputesReadBack() {
        paypal.stubFor(get("/v2/payments/captures/CAPTURE-1").willReturn(okJson("""
                {"id":"CAPTURE-1","status":"PARTIALLY_REFUNDED","custom_id":"prod:q-1",
                 "amount":{"currency_code":"USD","value":"12.50"},
                 "supplementary_data":{"related_ids":{"order_id":"ORDER-1"}}}""")));
        paypal.stubFor(get(urlPathEqualTo("/v1/customer/disputes")).willReturn(okJson("""
                {"items":[{"dispute_id":"PP-D-1","status":"OPEN"},{"dispute_id":"PP-D-2","status":"RESOLVED"}]}""")));

        PaypalCapture capture = client.getCapture(MERCHANT, "CAPTURE-1");
        List<PaypalDispute> disputes = client.disputesFor(MERCHANT, "CAPTURE-1");

        assertThat(capture.succeeded()).isTrue();
        assertThat(capture.completed()).isFalse();
        assertThat(capture.orderId()).isEqualTo("ORDER-1");
        assertThat(capture.currency()).isEqualTo("usd");
        assertThat(disputes).extracting(PaypalDispute::open).containsExactly(true, false);
        paypal.verify(getRequestedFor(urlPathEqualTo("/v1/customer/disputes"))
                .withQueryParam("disputed_transaction_id", equalTo("CAPTURE-1")));
    }

    @Test
    void idsAreEncodedAsOnePathSegment() {
        assertThat(PaypalHttp.segment("a/../b")).doesNotContain("/");
    }

    private static String order(String id, String status) {
        return """
                {"id":"%s","status":"%s",
                 "purchase_units":[{"custom_id":"prod:q-1","payee":{"merchant_id":"SELLER123"},
                   "amount":{"currency_code":"USD","value":"12.50"}}],
                 "links":[{"rel":"self","href":"https://api.sandbox.paypal.com/v2/checkout/orders/%s"},
                          {"rel":"payer-action","href":"https://www.sandbox.paypal.com/checkoutnow?token=%s"}]}"""
                .formatted(id, status, id, id);
    }

    private static String capturedOrder(String id, String captureId) {
        return """
                {"id":"%s","status":"COMPLETED",
                 "purchase_units":[{"custom_id":"prod:q-1","payee":{"merchant_id":"SELLER123"},
                   "amount":{"currency_code":"USD","value":"12.50"},
                   "payments":{"captures":[{"id":"%s","status":"COMPLETED","custom_id":"prod:q-1",
                     "amount":{"currency_code":"USD","value":"12.50"}}]}}]}"""
                .formatted(id, captureId);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
