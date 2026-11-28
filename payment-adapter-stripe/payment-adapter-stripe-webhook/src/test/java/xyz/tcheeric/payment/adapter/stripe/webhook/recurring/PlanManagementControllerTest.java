package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier;

/**
 * Reading and cancelling a plan from the wallet: the buyer is whoever signed the request, never a key the caller
 * names, and someone else's plan looks exactly like no plan at all.
 */
class PlanManagementControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long NOW = 1_800_000_000L;
    private static final String ORIGIN = "https://wallet.imani.casa";
    private static final byte[] BUYER_SK = HexFormat.of().parseHex("11".repeat(32));
    private static final byte[] OTHER_SK = HexFormat.of().parseHex("22".repeat(32));
    private static final byte[] STALL_SK = HexFormat.of().parseHex("33".repeat(32));

    private RecurringPlanRepository plans;
    private StripePurchaseRepository purchases;
    private StripeSubscriptionClient subscriptions;
    private PlanManagementController controller;
    private RecurringPlan plan;
    private String buyer;
    private String stall;

    @BeforeEach
    void setUp() throws Exception {
        buyer = HexFormat.of().formatHex(Schnorr.genPubKey(BUYER_SK));
        stall = HexFormat.of().formatHex(Schnorr.genPubKey(STALL_SK));
        plans = mock(RecurringPlanRepository.class);
        purchases = mock(StripePurchaseRepository.class);
        subscriptions = mock(StripeSubscriptionClient.class);
        controller = controller(List.of(ORIGIN + "/"));
        plan = new RecurringPlan();
        plan.setId(UUID.randomUUID());
        plan.setBuyerPubkey(buyer);
        plan.setStallPubkey(stall);
        plan.setConnectedAccountId("acct_farm");
        plan.setSubscriptionId("sub_1");
        plan.setStatus(Status.ACTIVE);
        plan.setAmountMinor(4000);
        plan.setCurrency("gbp");
        plan.setTotalInstalments(6);
        when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(plans.save(any(RecurringPlan.class))).thenAnswer(i -> i.getArgument(0));
    }

    private PlanManagementController controller(List<String> origins) {
        return new PlanManagementController(plans, purchases, subscriptions,
                new Nip98Verifier(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)), origins);
    }

    static String nip98(byte[] sk, String url, String method) throws Exception {
        String pubkey = HexFormat.of().formatHex(Schnorr.genPubKey(sk));
        ArrayNode tags = MAPPER.createArrayNode();
        tags.add(MAPPER.createArrayNode().add("u").add(url));
        tags.add(MAPPER.createArrayNode().add("method").add(method));
        ArrayNode canonical = MAPPER.createArrayNode().add(0).add(pubkey).add(NOW).add(27235);
        canonical.add(tags);
        canonical.add("");
        byte[] id = MessageDigest.getInstance("SHA-256")
                .digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
        ObjectNode event = MAPPER.createObjectNode();
        event.put("id", HexFormat.of().formatHex(id));
        event.put("pubkey", pubkey);
        event.put("created_at", NOW);
        event.put("kind", 27235);
        event.set("tags", tags);
        event.put("content", "");
        event.put("sig", HexFormat.of().formatHex(Schnorr.sign(id, sk, new byte[32])));
        return "Nostr " + Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(event));
    }

    private String planUrl(String suffix) {
        return ORIGIN + PlanManagementController.PATH + "/" + plan.getId() + suffix;
    }

    private HttpStatus cancelAs(byte[] sk) throws Exception {
        return status(controller.cancel(plan.getId().toString(), nip98(sk, planUrl("/cancel"), "POST")));
    }

    private static HttpStatus status(ResponseEntity<?> response) {
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    // The buyer's cancel ends the subscription at period end on the stall's account, and records that it was asked.
    @Test
    void theSignedBuyerCancelsAtPeriodEndOnTheStallsAccount() throws Exception {
        assertThat(cancelAs(BUYER_SK)).isEqualTo(HttpStatus.OK);

        verify(subscriptions).cancelAtPeriodEnd("acct_farm", "sub_1");
        assertThat(plan.getCancelRequestedAt()).isNotNull();
    }

    // Somebody else's signature gets 404, exactly like an unknown plan, so a plan id cannot be probed.
    @Test
    void anotherSignerGetsNotFoundAndNothingIsCancelled() throws Exception {
        assertThat(cancelAs(OTHER_SK)).isEqualTo(HttpStatus.NOT_FOUND);
        String unknown = UUID.randomUUID().toString();
        assertThat(status(controller.cancel(unknown, nip98(BUYER_SK,
                ORIGIN + PlanManagementController.PATH + "/" + unknown + "/cancel", "POST"))))
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
    }

    // No signature, a stale or wrong-URL one, or a GET signature replayed on the cancel: 401, nothing happens.
    @Test
    void anUnsignedOrMisboundRequestIsRefused() throws Exception {
        String id = plan.getId().toString();
        assertThat(status(controller.cancel(id, null))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(controller.cancel(id, nip98(BUYER_SK, planUrl(""), "POST")))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(controller.cancel(id, nip98(BUYER_SK, planUrl("/cancel"), "GET"))))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(controller.cancel(id, nip98(BUYER_SK, "https://evil.example" + PlanManagementController.PATH
                + "/" + id + "/cancel", "POST")))).isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
    }

    // With no public origin configured nothing can be verified, so the routes say so (503) rather than open up.
    @Test
    void noPublicOriginRefusesEverySignedRoute() throws Exception {
        PlanManagementController unconfigured = controller(List.of());

        assertThat(status(unconfigured.mine(nip98(BUYER_SK, ORIGIN + PlanManagementController.PATH, "GET"))))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(status(unconfigured.cancel(plan.getId().toString(), nip98(BUYER_SK, planUrl("/cancel"), "POST"))))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // A plan that already ended cannot be cancelled again.
    @Test
    void anEndedPlanCannotBeCancelledAgain() throws Exception {
        plan.setStatus(Status.COMPLETED);
        assertThat(cancelAs(BUYER_SK)).isEqualTo(HttpStatus.CONFLICT);
        plan.setStatus(Status.CANCELLED);
        assertThat(cancelAs(BUYER_SK)).isEqualTo(HttpStatus.CONFLICT);

        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
    }

    // An orphaned plan cannot be cancelled from here: saying "cancelled" would be false, the stall must do it.
    @Test
    void anOrphanedPlanIsNotPretendedCancelled() throws Exception {
        plan.setStatus(Status.ORPHANED);

        assertThat(cancelAs(BUYER_SK)).isEqualTo(HttpStatus.CONFLICT);
        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
        assertThat(plan.getStatus()).isEqualTo(Status.ORPHANED);
    }

    // If Stripe cannot be reached, the buyer is told, and the plan is not marked as cancel-requested.
    @Test
    void aStripeFailureIsReportedAndNotRecorded() throws Exception {
        doThrow(new RecurringPlanException("down")).when(subscriptions).cancelAtPeriodEnd(anyString(), anyString());

        assertThat(cancelAs(BUYER_SK)).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(plan.getCancelRequestedAt()).isNull();
    }

    // A second Stop on a plan already stopping does not call Stripe again: the answer is the same, nothing changes.
    @Test
    void aRepeatedStopIsIdempotent() throws Exception {
        Instant first = Instant.parse("2026-12-01T10:00:00Z");
        plan.setCancelRequestedAt(first);

        assertThat(cancelAs(BUYER_SK)).isEqualTo(HttpStatus.OK);

        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
        assertThat(plan.getCancelRequestedAt()).isEqualTo(first);
    }

    // A plan the buyer never finished paying for has no subscription, so cancelling just closes it.
    @Test
    void cancellingAPendingPlanWithNoSubscriptionJustClosesIt() throws Exception {
        plan.setSubscriptionId(null);
        plan.setStatus(Status.PENDING);

        assertThat(cancelAs(BUYER_SK)).isEqualTo(HttpStatus.OK);

        assertThat(plan.getStatus()).isEqualTo(Status.CANCELLED);
        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
        verify(subscriptions, never()).expireCheckoutSession(anyString(), anyString());
    }

    // Review regression: a pending plan whose Stripe page is still open has that page expired before it is closed,
    // or the buyer could finish it afterwards and start a subscription on a plan Imani already marked cancelled.
    @Test
    void cancellingAPendingPlanExpiresItsOpenCheckoutFirst() throws Exception {
        plan.setSubscriptionId(null);
        plan.setCheckoutSessionId("cs_open");
        plan.setStatus(Status.PENDING);

        assertThat(cancelAs(BUYER_SK)).isEqualTo(HttpStatus.OK);

        verify(subscriptions).expireCheckoutSession("acct_farm", "cs_open");
        assertThat(plan.getStatus()).isEqualTo(Status.CANCELLED);
    }

    // If the page cannot be expired (Stripe down, or the buyer just finished it), the plan stays pending: 502.
    @Test
    void aPendingPlanWhoseCheckoutCannotBeExpiredIsNotClosed() throws Exception {
        plan.setSubscriptionId(null);
        plan.setCheckoutSessionId("cs_done");
        plan.setStatus(Status.PENDING);
        doThrow(new RecurringPlanException("complete")).when(subscriptions).expireCheckoutSession(anyString(), anyString());

        assertThat(cancelAs(BUYER_SK)).isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(plan.getStatus()).isEqualTo(Status.PENDING);
        verify(plans, never()).save(any(RecurringPlan.class));
    }

    // Reading a plan answers its detail, with the vouchers it issued, only to the signer who owns it.
    @Test
    void readingAPlanNeedsTheBuyersSignature() throws Exception {
        StripePurchase paid = new StripePurchase();
        paid.setVoucherId("v-1");
        when(purchases.findByRecurringPlanIdAndVoucherIdIsNotNullOrderByCreatedAtAsc(plan.getId()))
                .thenReturn(List.of(paid));
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(1L);

        ResponseEntity<?> mine = controller.get(plan.getId().toString(), nip98(BUYER_SK, planUrl(""), "GET"));
        assertThat(status(mine)).isEqualTo(HttpStatus.OK);
        PlanManagementController.PlanDetail detail = (PlanManagementController.PlanDetail) mine.getBody();
        assertThat(detail.voucherIds()).containsExactly("v-1");
        assertThat(detail.plan().instalmentsPaid()).isEqualTo(1);
        assertThat(detail.plan().stallPubkey()).isEqualTo(stall);

        assertThat(status(controller.get(plan.getId().toString(), nip98(OTHER_SK, planUrl(""), "GET"))))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // The list is the signer's own plans, read by the key in the signature.
    @Test
    @SuppressWarnings("unchecked")
    void theListIsTheSignersOwnPlans() throws Exception {
        when(plans.findTop100ByBuyerPubkeyOrderByCreatedAtDesc(buyer)).thenReturn(List.of(plan));

        ResponseEntity<?> response = controller.mine(nip98(BUYER_SK, ORIGIN + PlanManagementController.PATH, "GET"));

        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        List<PlanManagementController.PlanView> views = (List<PlanManagementController.PlanView>) response.getBody();
        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.planId()).isEqualTo(plan.getId().toString());
            assertThat(v.status()).isEqualTo("ACTIVE");
            assertThat(v.amountMinor()).isEqualTo(4000);
            assertThat(v.totalInstalments()).isEqualTo(6);
        });
    }

    // The wire format is camelCase, like every other checkout answer the wallet reads.
    @Test
    void theViewIsCamelCaseOnTheWire() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(PlanManagementController.PlanView.of(plan, 2));

        assertThat(json).contains("\"planId\"", "\"instalmentsPaid\":2", "\"totalInstalments\":6", "\"stallPubkey\"");
        assertThat(json).doesNotContain("plan_id", "buyer");
    }

    // A stall reads its own count and monthly total of ACTIVE plans, per currency, and nobody else's.
    @Test
    void aStallReadsItsOwnSummaryOnly() throws Exception {
        RecurringPlan second = new RecurringPlan();
        second.setAmountMinor(3000);
        second.setCurrency("gbp");
        when(plans.findByStallPubkeyAndStatusIn(stall, List.of(Status.ACTIVE))).thenReturn(List.of(plan, second));
        String url = ORIGIN + PlanManagementController.PATH + "/summary?stall=" + stall;

        ResponseEntity<?> response = controller.summary(stall, nip98(STALL_SK, url, "GET"));

        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        PlanManagementController.StallSummary summary = (PlanManagementController.StallSummary) response.getBody();
        assertThat(summary.activePlans()).isEqualTo(2);
        assertThat(summary.monthlyTotals()).containsExactly(new PlanManagementController.CurrencyTotal("gbp", 7000));

        assertThat(status(controller.summary(stall, nip98(BUYER_SK, url, "GET")))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(controller.summary("nope", nip98(STALL_SK, url, "GET")))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // A weekly plan is counted but not folded into the monthly total: £10 a week is not £10 a month.
    @Test
    void aSummaryKeepsIntervalsApart() throws Exception {
        RecurringPlan weekly = new RecurringPlan();
        weekly.setAmountMinor(2500);
        weekly.setCurrency("gbp");
        weekly.setIntervalUnit("week");
        when(plans.findByStallPubkeyAndStatusIn(stall, List.of(Status.ACTIVE))).thenReturn(List.of(plan, weekly));
        String url = ORIGIN + PlanManagementController.PATH + "/summary?stall=" + stall;

        PlanManagementController.StallSummary summary = (PlanManagementController.StallSummary)
                controller.summary(stall, nip98(STALL_SK, url, "GET")).getBody();

        assertThat(summary.activePlans()).isEqualTo(2);
        assertThat(summary.monthlyTotals()).containsExactly(new PlanManagementController.CurrencyTotal("gbp", 4000));
        assertThat(summary.totals()).containsExactly(
                new PlanManagementController.IntervalTotal("gbp", "month", 4000),
                new PlanManagementController.IntervalTotal("gbp", "week", 2500));
    }

    // Each plan says how often it is paid.
    @Test
    void aPlanNamesItsInterval() throws Exception {
        plan.setIntervalUnit("year");
        when(plans.findTop100ByBuyerPubkeyOrderByCreatedAtDesc(buyer)).thenReturn(List.of(plan));

        String json = MAPPER.findAndRegisterModules().writeValueAsString(
                controller.mine(nip98(BUYER_SK, ORIGIN + PlanManagementController.PATH, "GET")).getBody());

        assertThat(json).contains("\"interval\":\"year\"");
    }
}
