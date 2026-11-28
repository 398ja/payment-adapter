package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.PlanCheckoutController.PlanRequest;

/**
 * Starting a plan refuses everything it can BEFORE the buyer sees a card form, and records the buyer on Imani's own
 * plan row before Stripe is called.
 */
class PlanCheckoutControllerTest {

    private static final String STALL = "a".repeat(64);
    private static final String BUYER = "b".repeat(64);

    private ConnectedStripeAccountRepository accounts;
    private RecurringPlanRepository plans;
    private StripeCheckoutService checkout;
    private StallReadinessClient readiness;
    private StripeRecurringProperties properties;
    private PlanCheckoutController controller;
    private ConnectedStripeAccount account;

    @BeforeEach
    void setUp() {
        accounts = mock(ConnectedStripeAccountRepository.class);
        plans = mock(RecurringPlanRepository.class);
        checkout = mock(StripeCheckoutService.class);
        readiness = mock(StallReadinessClient.class);
        properties = new StripeRecurringProperties();
        properties.setEnabled(true);
        controller = new PlanCheckoutController(accounts, plans, checkout, readiness, properties);

        account = new ConnectedStripeAccount();
        account.setMerchantPubkey(STALL);
        account.setStripeAccountId("acct_farm");
        account.setChargesEnabled(true);
        account.setDefaultCurrency("gbp");
        account.setFeePayer("account");
        account.setLossesPayer("stripe");
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.of(account));
        when(readiness.checkSale(anyString(), anyString(), anyLong())).thenReturn(StallReadinessClient.Readiness.ready());
        when(plans.save(any(RecurringPlan.class))).thenAnswer(i -> i.getArgument(0));
        when(checkout.createPlanSession(anyString(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(StripeCheckoutSession.builder().sessionId("cs_plan").sessionUrl("https://checkout.stripe.com/x").build());
    }

    private static PlanRequest request(long amount, Integer instalments) {
        return new PlanRequest(STALL, BUYER, amount, instalments);
    }

    // The happy path: the plan is recorded with the buyer BEFORE Stripe is called, and the buyer is sent to Stripe.
    @Test
    void recordsThePlanAndTheBuyerBeforeCallingStripe() {
        ResponseEntity<?> response = controller.start(request(5000, 6));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<RecurringPlan> saved = ArgumentCaptor.forClass(RecurringPlan.class);
        InOrder order = inOrder(plans, checkout);
        order.verify(plans).save(saved.capture());
        order.verify(checkout).createPlanSession(anyString(), eq(5000L), anyString(), eq("gbp"), eq("acct_farm"), any(), any(), any());
        assertThat(saved.getAllValues().get(0).getBuyerPubkey()).isEqualTo(BUYER);
        assertThat(saved.getAllValues().get(0).getStallPubkey()).isEqualTo(STALL);
        assertThat(saved.getAllValues().get(0).getTotalInstalments()).isEqualTo(6);
        PlanCheckoutController.PlanResponse ok = (PlanCheckoutController.PlanResponse) response.getBody();
        assertThat(ok.checkoutUrl()).isEqualTo("https://checkout.stripe.com/x");
        assertThat(ok.planId()).isEqualTo(saved.getAllValues().get(0).getId().toString());
    }

    // A legacy Express account is refused with no plan row and no call to Stripe.
    @Test
    void anAccountWhereImaniWouldBearFeesIsRefusedBeforeAnything() {
        account.setFeePayer("application_express");
        account.setLossesPayer("application");

        ResponseEntity<?> response = controller.start(request(5000, 6));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(plans, never()).save(any());
        verify(checkout, never()).createPlanSession(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    // A buyer with no wallet key cannot be delivered vouchers by claim link on a recurring plan, so it is refused.
    @Test
    void aBuyerWithoutAWalletKeyIsRefused() {
        ResponseEntity<?> response = controller.start(new PlanRequest(STALL, "not-a-key", 5000L, 6));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(plans, never()).save(any());
    }

    // Instalments below the minimum are refused, because the 20p per payment makes them expensive for the stall.
    @Test
    void anInstalmentBelowTheMinimumIsRefused() {
        ResponseEntity<?> refused = controller.start(request(1999, 6));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The wallet names the minimum from the answer, not from a number it guessed.
        assertThat((java.util.Map<String, Object>) refused.getBody())
                .containsEntry("code", "BELOW_MINIMUM").containsEntry("minAmountMinor", 2000L);
        assertThat(controller.start(request(2000, 6)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // The start answer is camelCase on the wire, as a one-off purchase answers, so the wallet reads both alike.
    @Test
    void theStartAnswerIsCamelCaseOnTheWire() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(new PlanCheckoutController.PlanResponse("https://checkout.stripe.com/x", "p1"));

        assertThat(json).isEqualTo("{\"checkoutUrl\":\"https://checkout.stripe.com/x\",\"planId\":\"p1\"}");
    }

    // An ineligible account and an unconnected stall carry a code, so the wallet can show its own words.
    @Test
    void refusalsCarryACodeTheWalletCanBranchOn() {
        account.setFeePayer("application_express");
        assertThat((java.util.Map<String, Object>) controller.start(request(5000, 6)).getBody())
                .containsEntry("code", "ACCOUNT_NOT_ELIGIBLE");

        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.empty());
        assertThat((java.util.Map<String, Object>) controller.start(request(5000, 6)).getBody())
                .containsEntry("code", "NOT_CONNECTED");
    }

    // A fixed term is 2 to the maximum instalments; an open-ended plan leaves it out.
    @Test
    void theTermIsBoundedButMayBeLeftOut() {
        assertThat(controller.start(request(5000, 1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.start(request(5000, 25)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.start(request(5000, 24)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.start(request(5000, null)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // A stall that cannot take cards yet is refused, as for a one-off purchase.
    @Test
    void aStallThatCannotTakeCardsIsRefused() {
        account.setChargesEnabled(false);

        assertThat(controller.start(request(5000, 6)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(plans, never()).save(any());
    }

    // An unknown stall answers 404.
    @Test
    void anUnknownStallIsNotFound() {
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.empty());

        assertThat(controller.start(request(5000, 6)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // A readiness answer of "unknown" fails closed: the buyer is not sent to pay for vouchers that cannot be issued.
    @Test
    void anUnknownReadinessFailsClosed() {
        when(readiness.checkSale(anyString(), anyString(), anyLong())).thenReturn(StallReadinessClient.Readiness.unknown());

        assertThat(controller.start(request(5000, 6)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(plans, never()).save(any());
    }

    // A stall not ready to sell is refused with the same words a one-off purchase uses.
    @Test
    void aStallNotReadyToSellIsRefused() {
        when(readiness.checkSale(anyString(), anyString(), anyLong()))
                .thenReturn(StallReadinessClient.Readiness.notReady(StallReadinessClient.NOT_IN_PLAN));

        assertThat(controller.start(request(5000, 6)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(checkout, never()).createPlanSession(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    // If Stripe cannot create the session, no subscription exists, so the plan row is closed and not left pending.
    @Test
    void aStripeFailureClosesThePlanRow() {
        when(checkout.createPlanSession(anyString(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("stripe down"));

        ResponseEntity<?> response = controller.start(request(5000, 6));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        ArgumentCaptor<RecurringPlan> saved = ArgumentCaptor.forClass(RecurringPlan.class);
        verify(plans, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RecurringPlan.Status.CANCELLED);
    }

    // A plan always returns the buyer to the deployment's own pages: no caller-supplied URL reaches Stripe.
    @Test
    void theReturnUrlsAreAlwaysTheDeploymentsOwn() {
        controller.start(request(5000, 6));

        ArgumentCaptor<String> success = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cancel = ArgumentCaptor.forClass(String.class);
        verify(checkout).createPlanSession(anyString(), anyLong(), anyString(), anyString(), anyString(), any(),
                success.capture(), cancel.capture());
        assertThat(success.getValue()).isNull();
        assertThat(cancel.getValue()).isNull();
    }
}
