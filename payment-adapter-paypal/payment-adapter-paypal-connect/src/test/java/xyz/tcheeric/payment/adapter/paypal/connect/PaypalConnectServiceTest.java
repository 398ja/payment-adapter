package xyz.tcheeric.payment.adapter.paypal.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ProcessedPaypalWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedPaypalWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.paypal.connect.config.PaypalConnectProperties;
import xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectException;
import xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectExceptionCode;
import xyz.tcheeric.payment.adapter.paypal.connect.model.PaypalConnectAccountResponse;
import xyz.tcheeric.payment.adapter.paypal.connect.model.PaypalConnectWebhookResponse;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalSignatureException;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalWebhookVerifier;

class PaypalConnectServiceTest {

    static final String STALL = "npub-stall";

    PaypalConnectProperties connect;
    PaypalPartnerClient partner;
    PaypalWebhookVerifier verifier;
    ConnectedPaypalAccountRepository accounts;
    ProcessedPaypalWebhookEventRepository events;
    PaypalConnectService service;
    Map<String, ConnectedPaypalAccount> byPubkey = new HashMap<>();
    Map<String, ProcessedPaypalWebhookEvent> eventStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        connect = new PaypalConnectProperties();
        connect.setEnabled(true);
        connect.setReturnUrl("https://wallet.example/payments");
        PaypalGatewayProperties gateway = new PaypalGatewayProperties();
        partner = mock(PaypalPartnerClient.class);
        verifier = mock(PaypalWebhookVerifier.class);
        accounts = mock(ConnectedPaypalAccountRepository.class);
        events = mock(ProcessedPaypalWebhookEventRepository.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        lenient().when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        lenient().when(accounts.save(any())).thenAnswer(inv -> {
            ConnectedPaypalAccount a = inv.getArgument(0);
            byPubkey.put(a.getMerchantPubkey(), a);
            return a;
        });
        lenient().when(accounts.findByMerchantPubkey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byPubkey.get(inv.<String>getArgument(0))));
        lenient().when(accounts.findByPaypalMerchantId(anyString())).thenAnswer(inv -> byPubkey.values().stream()
                .filter(a -> inv.getArgument(0).equals(a.getPaypalMerchantId())).findFirst());
        lenient().when(accounts.findByTrackingId(anyString())).thenAnswer(inv -> byPubkey.values().stream()
                .filter(a -> inv.getArgument(0).equals(a.getTrackingId())).findFirst());
        lenient().when(events.save(any())).thenAnswer(inv -> {
            ProcessedPaypalWebhookEvent e = inv.getArgument(0);
            eventStore.put(e.getEventId(), e);
            return e;
        });
        lenient().when(events.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(eventStore.get(inv.<String>getArgument(0))));
        lenient().when(partner.createReferral(anyString(), anyString())).thenReturn("https://paypal.example/onboard");

        service = new PaypalConnectService(connect, gateway, partner, verifier, new ObjectMapper(), accounts, events, tx);
    }

    @Test
    void aNewStallGetsATrackingIdAndAnOnboardingLink() {
        PaypalConnectAccountResponse response = service.createOrResume(STALL, null);

        ConnectedPaypalAccount saved = byPubkey.get(STALL);
        assertThat(saved.getTrackingId()).startsWith("stall-");
        assertThat(response.onboardingUrl()).isEqualTo("https://paypal.example/onboard");
        assertThat(response.status()).isEqualTo("onboarding_in_progress");
        assertThat(response.chargesEnabled()).isFalse();
        verify(partner).createReferral(saved.getTrackingId(), "https://wallet.example/payments");
    }

    @Test
    void aFinishedSellerIsFoundByTrackingIdAndCanTakeCards() {
        service.createOrResume(STALL, null);
        String tracking = byPubkey.get(STALL).getTrackingId();
        when(partner.findMerchantId(tracking)).thenReturn(Optional.of("SELLER123"));
        when(partner.sellerStatus("SELLER123")).thenReturn(status("SELLER123", tracking, true));

        PaypalConnectAccountResponse response = service.getStatus(STALL);

        assertThat(response.paypalMerchantId()).isEqualTo("SELLER123");
        assertThat(response.chargesEnabled()).isTrue();
        assertThat(response.onboardingComplete()).isTrue();
        assertThat(response.status()).isEqualTo("payouts_enabled");
    }

    @Test
    void aSellerStatusNamingAnotherStallsTrackingIdIsRefused() {
        service.createOrResume(STALL, null);
        String tracking = byPubkey.get(STALL).getTrackingId();
        when(partner.findMerchantId(tracking)).thenReturn(Optional.of("SELLER123"));
        when(partner.sellerStatus("SELLER123")).thenReturn(status("SELLER123", "stall-someone-else", true));

        assertThatThrownBy(() -> service.getStatus(STALL)).isInstanceOfSatisfying(PaypalConnectException.class,
                e -> assertThat(e.getCode()).isEqualTo(PaypalConnectExceptionCode.OWNERSHIP_MISMATCH));
        assertThat(byPubkey.get(STALL).getPaypalMerchantId()).isNull();
    }

    @Test
    void aSellerAlreadyClaimedByAnotherStallIsRefused() {
        ConnectedPaypalAccount other = account("npub-other", "stall-other", "SELLER123");
        byPubkey.put(other.getMerchantPubkey(), other);
        service.createOrResume(STALL, null);
        String tracking = byPubkey.get(STALL).getTrackingId();
        when(partner.findMerchantId(tracking)).thenReturn(Optional.of("SELLER123"));
        when(partner.sellerStatus("SELLER123")).thenReturn(status("SELLER123", null, true));

        assertThatThrownBy(() -> service.getStatus(STALL)).isInstanceOf(PaypalConnectException.class);
    }

    @Test
    void notConnectedIsAnAnswerNotAnError() {
        PaypalConnectAccountResponse response = service.getStatus("npub-nobody");
        assertThat(response.status()).isEqualTo("not_connected");
    }

    @Test
    void disabledRefusesEverything() {
        connect.setEnabled(false);
        assertThatThrownBy(() -> service.getStatus(STALL)).isInstanceOfSatisfying(PaypalConnectException.class,
                e -> assertThat(e.getCode()).isEqualTo(PaypalConnectExceptionCode.CONNECT_DISABLED));
    }

    @Test
    void anOnboardingWebhookRereadsTheSellerFromPaypal() {
        ConnectedPaypalAccount mine = account(STALL, "stall-1", null);
        byPubkey.put(STALL, mine);
        when(partner.sellerStatus("SELLER123")).thenReturn(status("SELLER123", "stall-1", true));

        PaypalConnectWebhookResponse response = service.handleWebhook(body("WH-1", "MERCHANT.ONBOARDING.COMPLETED",
                "{\"merchant_id\":\"SELLER123\",\"tracking_id\":\"stall-1\"}"), h -> null);

        assertThat(response.status()).isEqualTo("processed");
        assertThat(mine.getPaypalMerchantId()).isEqualTo("SELLER123");
        assertThat(mine.isChargesEnabled()).isTrue();
        assertThat(eventStore.get("WH-1").getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.PROCESSED);
    }

    @Test
    void aRedeliveredEventIsADuplicate() {
        byPubkey.put(STALL, account(STALL, "stall-1", null));
        when(partner.sellerStatus("SELLER123")).thenReturn(status("SELLER123", "stall-1", true));
        byte[] body = body("WH-1", "MERCHANT.ONBOARDING.COMPLETED",
                "{\"merchant_id\":\"SELLER123\",\"tracking_id\":\"stall-1\"}");

        service.handleWebhook(body, h -> null);
        PaypalConnectWebhookResponse again = service.handleWebhook(body, h -> null);

        assertThat(again.status()).isEqualTo("duplicate");
        verify(partner, org.mockito.Mockito.times(1)).sellerStatus("SELLER123");
    }

    @Test
    void consentRevokedForgetsTheSeller() {
        ConnectedPaypalAccount mine = account(STALL, "stall-1", "SELLER123");
        byPubkey.put(STALL, mine);

        service.handleWebhook(body("WH-2", "MERCHANT.PARTNER-CONSENT.REVOKED", "{\"merchant_id\":\"SELLER123\"}"),
                h -> null);

        verify(accounts).delete(mine);
    }

    // A seller with instalment plans still charging buyers cannot disconnect (ADR 0003).
    @Test
    void disconnectIsBlockedWhilePlansAreRunning() {
        byPubkey.put(STALL, account(STALL, "stall-1", "SELLER123"));
        RecurringPlanRepository plans = mock(RecurringPlanRepository.class);
        when(plans.existsByConnectedAccountIdAndStatusIn("SELLER123", RecurringPlan.CHARGING)).thenReturn(true);
        service.setRecurringPlans(plans);

        assertThatThrownBy(() -> service.disconnect(STALL)).isInstanceOf(PaypalConnectException.class)
                .extracting("code").isEqualTo(PaypalConnectExceptionCode.ACTIVE_PLANS);
        verify(accounts, never()).deleteByMerchantPubkey(STALL);
    }

    // A revoked consent orphans every live PayPal plan on the seller, so a person follows them up.
    @Test
    void consentRevokedOrphansThePlans() {
        byPubkey.put(STALL, account(STALL, "stall-1", "SELLER123"));
        RecurringPlanRepository plans = mock(RecurringPlanRepository.class);
        RecurringPlan plan = new RecurringPlan();
        plan.setProcessor(RecurringPlan.PROCESSOR_PAYPAL);
        plan.setStatus(RecurringPlan.Status.ACTIVE);
        when(plans.findByConnectedAccountIdAndStatusIn("SELLER123", RecurringPlan.LIVE)).thenReturn(List.of(plan));
        service.setRecurringPlans(plans);

        service.handleWebhook(body("WH-9", "MERCHANT.PARTNER-CONSENT.REVOKED", "{\"merchant_id\":\"SELLER123\"}"),
                h -> null);

        assertThat(plan.getStatus()).isEqualTo(RecurringPlan.Status.ORPHANED);
    }

    @Test
    void unhandledEventsAreAcknowledged() {
        PaypalConnectWebhookResponse response = service.handleWebhook(
                body("WH-3", "PAYMENT.SALE.COMPLETED", "{}"), h -> null);
        assertThat(response.status()).isEqualTo("ignored");
    }

    @Test
    void aBadSignatureIsRefusedBeforeAnythingIsRecorded() {
        doThrow(new PaypalSignatureException("nope")).when(verifier).verify(any(), any());

        assertThatThrownBy(() -> service.handleWebhook(body("WH-4", "MERCHANT.ONBOARDING.COMPLETED", "{}"), h -> null))
                .isInstanceOfSatisfying(PaypalConnectException.class,
                        e -> assertThat(e.getCode()).isEqualTo(PaypalConnectExceptionCode.WEBHOOK_SIGNATURE_INVALID));
        verify(events, never()).save(any());
    }

    @Test
    void aFailedEventIsMarkedFailedAndRethrown() {
        byPubkey.put(STALL, account(STALL, "stall-1", null));
        when(partner.sellerStatus("SELLER123")).thenThrow(PaypalConnectException.apiError("PayPal down", null));

        assertThatThrownBy(() -> service.handleWebhook(body("WH-5", "MERCHANT.ONBOARDING.COMPLETED",
                "{\"merchant_id\":\"SELLER123\",\"tracking_id\":\"stall-1\"}"), h -> null))
                .isInstanceOf(PaypalConnectException.class);
        assertThat(eventStore.get("WH-5").getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.FAILED);
    }

    @Test
    void anEventForASellerWeDoNotKnowIsIgnored() {
        byPubkey.put(STALL, account(STALL, "stall-1", null));

        PaypalConnectWebhookResponse response = service.handleWebhook(
                body("WH-6", "MERCHANT.ONBOARDING.COMPLETED", "{\"merchant_id\":\"STRANGER\"}"), h -> null);

        assertThat(response.status()).isEqualTo("processed");
        verify(partner, never()).sellerStatus(anyString());
    }

    static PaypalSellerStatus status(String merchantId, String trackingId, boolean ready) {
        return new PaypalSellerStatus(merchantId, trackingId, ready, ready,
                ready ? Set.of(PaypalSellerStatus.PAYMENT_SCOPE) : Set.of(), "seller@example.com", "US");
    }

    static ConnectedPaypalAccount account(String pubkey, String tracking, String merchantId) {
        ConnectedPaypalAccount a = new ConnectedPaypalAccount();
        a.setMerchantPubkey(pubkey);
        a.setTrackingId(tracking);
        a.setPaypalMerchantId(merchantId);
        a.setRequirementsDue("[]");
        return a;
    }

    static byte[] body(String id, String type, String resource) {
        return ("{\"id\":\"" + id + "\",\"event_type\":\"" + type + "\",\"resource\":" + resource + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static Function<String, String> noHeaders() {
        return h -> null;
    }
}
