package xyz.tcheeric.payment.adapter.paypal.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StallInstalmentSettings;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StallInstalmentSettingsRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSubscription;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionRequest;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionsClient;
import xyz.tcheeric.payment.adapter.paypal.webhook.recurring.PaypalPlanCheckoutController.PlanRequest;
import xyz.tcheeric.payment.adapter.paypal.webhook.recurring.PaypalPlanCheckoutController.PlanResponse;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient.Readiness;

class PaypalPlanCheckoutControllerTest {

    static final String STALL = "a".repeat(64);
    static final String BUYER = "b".repeat(64);
    static final String MERCHANT = "SELLER123";

    ConnectedPaypalAccountRepository accounts;
    RecurringPlanRepository plans;
    PaypalSubscriptionsClient subscriptions;
    StallReadinessClient readiness;
    StallInstalmentSettingsRepository settings;
    PaypalGatewayProperties paypal;
    PaypalPlanCheckoutController controller;
    List<RecurringPlan> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        accounts = mock(ConnectedPaypalAccountRepository.class);
        plans = mock(RecurringPlanRepository.class);
        subscriptions = mock(PaypalSubscriptionsClient.class);
        readiness = mock(StallReadinessClient.class);
        settings = mock(StallInstalmentSettingsRepository.class);
        paypal = new PaypalGatewayProperties();
        paypal.setAllowedCurrencies(new ArrayList<>(List.of("gbp")));
        paypal.setSuccessUrl("https://wallet.example/paid?order={ORDER_ID}");
        paypal.setCancelUrl("https://wallet.example/cancel");
        paypal.setEnvironment("test");
        controller = new PaypalPlanCheckoutController(accounts, plans, subscriptions, readiness,
                new PaypalRecurringProperties(), paypal, settings);

        ConnectedPaypalAccount account = new ConnectedPaypalAccount();
        account.setMerchantPubkey(STALL);
        account.setPaypalMerchantId(MERCHANT);
        account.setChargesEnabled(true);
        account.setDefaultCurrency("gbp");
        lenient().when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.of(account));
        StallInstalmentSettings offer = new StallInstalmentSettings();
        offer.setStallPubkey(STALL);
        offer.setEnabled(true);
        offer.setIntervals("month");
        lenient().when(settings.findById(STALL)).thenReturn(Optional.of(offer));
        lenient().when(readiness.checkSale(anyString(), anyString(), anyLong())).thenReturn(Readiness.ready());
        lenient().when(plans.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    // A valid request writes the plan first, then starts the subscription as the seller and returns PayPal's page.
    @Test
    void aPlanIsStartedOnTheStallsOwnPaypalAccount() {
        when(subscriptions.startSubscription(any())).thenReturn(
                new PaypalSubscription("I-1", "APPROVAL_PENDING", null, "P-1", "https://paypal.example/approve"));

        ResponseEntity<?> response = controller.start(new PlanRequest(STALL, BUYER, 5000L, 6, "month"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((PlanResponse) response.getBody()).checkoutUrl()).isEqualTo("https://paypal.example/approve");
        ArgumentCaptor<PaypalSubscriptionRequest> sent = ArgumentCaptor.forClass(PaypalSubscriptionRequest.class);
        verify(subscriptions).startSubscription(sent.capture());
        assertThat(sent.getValue().merchantId()).isEqualTo(MERCHANT);
        assertThat(sent.getValue().totalCycles()).isEqualTo(6);
        assertThat(sent.getValue().returnUrl()).isEqualTo("https://wallet.example/paid?order=");
        RecurringPlan plan = saved.get(saved.size() - 1);
        assertThat(plan.getProcessor()).isEqualTo(RecurringPlan.PROCESSOR_PAYPAL);
        assertThat(plan.getSubscriptionId()).isEqualTo("I-1");
        assertThat(plan.getBuyerPubkey()).isEqualTo(BUYER);
        assertThat(sent.getValue().customId()).isEqualTo("test:" + plan.getId());
    }

    // A stall that never switched instalment plans on is refused before PayPal is called.
    @Test
    void aStallThatDoesNotOfferPlansIsRefused() {
        when(settings.findById(STALL)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.start(new PlanRequest(STALL, BUYER, 5000L, 6, "month"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("code", "NOT_OFFERED");
        verify(subscriptions, never()).startSubscription(any());
    }

    // An interval the stall does not offer is refused with the intervals it does.
    @Test
    void anIntervalNotOfferedIsRefused() {
        ResponseEntity<?> response = controller.start(new PlanRequest(STALL, BUYER, 5000L, 6, "week"));

        assertThat((Map<String, Object>) response.getBody()).containsEntry("code", "INTERVAL_NOT_OFFERED");
    }

    // An instalment below the minimum is refused with the minimum.
    @Test
    void aSmallInstalmentIsRefused() {
        ResponseEntity<?> response = controller.start(new PlanRequest(STALL, BUYER, 100L, 6, "month"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("code", "BELOW_MINIMUM");
    }

    // A stall with no PayPal seller account is not connected.
    @Test
    void aStallWithoutPaypalIsNotConnected() {
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.start(new PlanRequest(STALL, BUYER, 5000L, 6, "month"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // When PayPal fails, the plan is closed so nothing is left pending, and the buyer is asked to retry.
    @Test
    void aPaypalFailureClosesThePlan() {
        when(subscriptions.startSubscription(any()))
                .thenThrow(new PaypalApiException("down", 503, null, null, null));

        ResponseEntity<?> response = controller.start(new PlanRequest(STALL, BUYER, 5000L, null, "month"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(saved.get(saved.size() - 1).getStatus()).isEqualTo(RecurringPlan.Status.CANCELLED);
    }
}
