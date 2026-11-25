package xyz.tcheeric.payment.adapter.paypal.connect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PaypalSellerStatusTest {

    static final String US = "platform-client";
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aFullyOnboardedSellerCanTakeCards() throws Exception {
        PaypalSellerStatus status = PaypalSellerStatus.from(json(true, true, US), US);

        assertThat(PaypalCardCapability.canTakeCards(status)).isTrue();
        assertThat(status.requirementsDue()).isEmpty();
        assertThat(status.disabledReason()).isNull();
        assertThat(status.country()).isEqualTo("US");
        assertThat(status.trackingId()).isEqualTo("stall-1");
    }

    @Test
    void anUnconfirmedEmailHoldsCards() throws Exception {
        PaypalSellerStatus status = PaypalSellerStatus.from(json(true, false, US), US);

        assertThat(PaypalCardCapability.canTakeCards(status)).isFalse();
        assertThat(status.requirementsDue()).containsExactly("primary_email_confirmation");
    }

    @Test
    void aRestrictedAccountCannotTakeCards() throws Exception {
        PaypalSellerStatus status = PaypalSellerStatus.from(json(false, true, US), US);

        assertThat(PaypalCardCapability.canTakeCards(status)).isFalse();
        assertThat(status.disabledReason()).isEqualTo("payments_not_receivable");
    }

    @Test
    void permissionGrantedToAnotherPartnerSaysNothingAboutUs() throws Exception {
        PaypalSellerStatus status = PaypalSellerStatus.from(json(true, true, "someone-else"), US);

        assertThat(status.grants(PaypalSellerStatus.PAYMENT_SCOPE)).isFalse();
        assertThat(PaypalCardCapability.canTakeCards(status)).isFalse();
        assertThat(status.requirementsDue()).containsExactly("payment_permission");
    }

    @Test
    void noStatusOrNoMerchantIsNeverCards() throws Exception {
        assertThat(PaypalCardCapability.canTakeCards(null)).isFalse();
        JsonNode noMerchant = JSON.readTree("{\"payments_receivable\":true,\"primary_email_confirmed\":true}");
        assertThat(PaypalCardCapability.canTakeCards(PaypalSellerStatus.from(noMerchant, US))).isFalse();
    }

    static JsonNode json(boolean receivable, boolean emailConfirmed, String partnerClientId) throws Exception {
        return JSON.readTree("""
                {"merchant_id":"SELLER123","tracking_id":"stall-1","payments_receivable":%s,
                 "primary_email_confirmed":%s,"primary_email":"seller@example.com","country":"us",
                 "oauth_integrations":[{"integration_type":"OAUTH_THIRD_PARTY","oauth_third_party":[
                   {"partner_client_id":"%s","scopes":["%s","%s"]}]}]}"""
                .formatted(receivable, emailConfirmed, partnerClientId, PaypalSellerStatus.PAYMENT_SCOPE,
                        PaypalSellerStatus.REFUND_SCOPE));
    }
}
