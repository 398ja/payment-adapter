package xyz.tcheeric.payment.adapter.stripe.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeValidationException;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripePlanCheckoutRequest;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutClient;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;

/**
 * An instalment plan is a DIRECT charge on the stall's account, like a one-off purchase, and carries Imani's plan
 * id so every renewal invoice can name the plan. These tests pin both.
 */
class PlanSessionTest {

    private static final String ACCOUNT = "acct_1Farm";

    private StripeCheckoutClient client;
    private StripeCheckoutService service;
    private StripeGatewayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new StripeGatewayProperties();
        properties.setSuccessUrl("https://wallet.example/ok");
        properties.setCancelUrl("https://wallet.example/no");
        properties.setDefaultCurrency("GBP");
        properties.setAllowedCurrencies(List.of("gbp", "usd"));
        properties.setMinAmountMinor(1000);
        properties.setMaxAmountMinor(100000);
        client = mock(StripeCheckoutClient.class);
        service = new StripeCheckoutService(properties, client);
        when(client.createPlanCheckoutSession(any())).thenReturn(StripeCheckoutSession.builder().sessionId("cs_plan").build());
    }

    private StripePlanCheckoutRequest sent() {
        ArgumentCaptor<StripePlanCheckoutRequest> captor = ArgumentCaptor.forClass(StripePlanCheckoutRequest.class);
        verify(client).createPlanCheckoutSession(captor.capture());
        return captor.getValue();
    }

    // A plan must name the stall's connected account, so the charge is direct and Imani holds no float.
    @Test
    void aPlanIsADirectChargeOnTheStallsAccount() {
        service.createPlanSession("plan-1", 5000, "Veg box", "GBP", ACCOUNT, Map.of("buyer_pubkey", "ab"), null, null);

        assertThat(sent().getConnectedAccountId()).isEqualTo(ACCOUNT);
    }

    // The plan id rides in the metadata (session and subscription), and is also the idempotency key, so a retry
    // of the same request cannot start a second plan.
    @Test
    void thePlanIdIsCarriedAndIsTheIdempotencyKey() {
        service.createPlanSession("plan-1", 5000, "Veg box", "GBP", ACCOUNT, Map.of(), null, null);

        StripePlanCheckoutRequest request = sent();
        assertThat(request.getMetadata()).containsEntry("plan_id", "plan-1");
        assertThat(request.getIdempotencyKey()).isEqualTo("stripe:plan:plan-1");
    }

    // A caller cannot overwrite the plan id or the deployment's environment stamp.
    @Test
    void callerMetadataCannotOverwritePlanIdOrEnvironment() {
        properties.setEnvironment("staging");

        service.createPlanSession("plan-1", 5000, "Veg box", "GBP", ACCOUNT,
                Map.of("plan_id", "forged", StripeGatewayProperties.ENVIRONMENT_METADATA_KEY, "prod"), null, null);

        Map<String, String> metadata = sent().getMetadata();
        assertThat(metadata).containsEntry("plan_id", "plan-1");
        assertThat(metadata).containsEntry(StripeGatewayProperties.ENVIRONMENT_METADATA_KEY, "staging");
    }

    // Instalments below the deployment's minimum are refused here, before the buyer reaches Stripe.
    @Test
    void anInstalmentBelowTheMinimumIsRefused() {
        assertThatThrownBy(() -> service.createPlanSession("p", 999, "x", "GBP", ACCOUNT, Map.of(), null, null))
                .isInstanceOf(StripeValidationException.class);
        verify(client, never()).createPlanCheckoutSession(any());
    }

    // A plan with no connected account would be a platform charge, which is refused.
    @Test
    void aPlanWithoutAConnectedAccountIsRefused() {
        assertThatThrownBy(() -> service.createPlanSession("p", 5000, "x", "GBP", " ", Map.of(), null, null))
                .isInstanceOf(StripeValidationException.class);
        verify(client, never()).createPlanCheckoutSession(any());
    }

    // A currency the deployment does not allow is refused before any call to Stripe.
    @Test
    void aDisallowedCurrencyIsRefused() {
        assertThatThrownBy(() -> service.createPlanSession("p", 5000, "x", "JPY", ACCOUNT, Map.of(), null, null))
                .isInstanceOf(StripeValidationException.class);
    }

    // Return URLs fall back to the deployment's purchase URLs when the caller gives none.
    @Test
    void returnUrlsFallBackToTheDeploymentsPurchaseUrls() {
        service.createPlanSession("plan-1", 5000, "Veg box", "GBP", ACCOUNT, Map.of(), null, null);

        assertThat(sent().getSuccessUrl()).isNotBlank();
        assertThat(sent().getCancelUrl()).isNotBlank();
    }
}
