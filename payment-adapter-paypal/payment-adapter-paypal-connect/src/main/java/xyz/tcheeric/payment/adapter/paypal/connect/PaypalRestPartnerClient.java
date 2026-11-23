package xyz.tcheeric.payment.adapter.paypal.connect;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.tcheeric.payment.adapter.paypal.connect.config.PaypalConnectProperties;
import xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectException;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalHttp;

/** {@link PaypalPartnerClient} over Partner Referrals v2 and the merchant-integrations API. */
public class PaypalRestPartnerClient implements PaypalPartnerClient {

    private final PaypalHttp http;
    private final PaypalConnectProperties properties;

    public PaypalRestPartnerClient(PaypalHttp http, PaypalConnectProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String createReferral(String trackingId, String returnUrl) {
        Map<String, Object> thirdParty = Map.of("features", properties.getFeatures());
        Map<String, Object> rest = new LinkedHashMap<>();
        rest.put("integration_method", "PAYPAL");
        rest.put("integration_type", "THIRD_PARTY");
        rest.put("third_party_details", thirdParty);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tracking_id", trackingId);
        body.put("operations", List.of(Map.of(
                "operation", "API_INTEGRATION",
                "api_integration_preference", Map.of("rest_api_integration", rest))));
        body.put("products", properties.getProducts());
        body.put("legal_consents", List.of(Map.of("type", "SHARE_DATA_CONSENT", "granted", true)));
        if (returnUrl != null && !returnUrl.isBlank()) {
            body.put("partner_config_override", Map.of("return_url", returnUrl));
        }
        JsonNode answer;
        try {
            answer = http.post("/v2/customer/partner-referrals", body).send();
        } catch (PaypalApiException e) {
            throw PaypalConnectException.apiError("PayPal refused the onboarding referral: " + e.getMessage(), e);
        }
        for (JsonNode link : answer.path("links")) {
            if ("action_url".equalsIgnoreCase(link.path("rel").asText())) {
                String href = link.path("href").asText(null);
                if (href != null && !href.isBlank()) {
                    return href;
                }
            }
        }
        throw PaypalConnectException.apiError("PayPal returned no onboarding link", null);
    }

    @Override
    public Optional<String> findMerchantId(String trackingId) {
        try {
            JsonNode answer = http.get("/v1/customer/partners/" + partner() + "/merchant-integrations")
                    .query("tracking_id", trackingId)
                    .send();
            String merchantId = answer.path("merchant_id").asText(null);
            return merchantId == null || merchantId.isBlank() ? Optional.empty() : Optional.of(merchantId);
        } catch (PaypalApiException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw PaypalConnectException.apiError("PayPal seller lookup failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PaypalSellerStatus sellerStatus(String merchantId) {
        try {
            JsonNode answer = http.get("/v1/customer/partners/" + partner() + "/merchant-integrations/"
                    + PaypalHttp.segment(merchantId)).send();
            return PaypalSellerStatus.from(answer, http.properties().getClientId());
        } catch (PaypalApiException e) {
            if (e.isNotFound()) {
                throw PaypalConnectException.accountNotFound(merchantId, e);
            }
            throw PaypalConnectException.apiError("PayPal seller status failed: " + e.getMessage(), e);
        }
    }

    private String partner() {
        return PaypalHttp.segment(properties.getPartnerMerchantId().trim());
    }
}
