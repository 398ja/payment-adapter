package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringEligibility;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseCheckoutController;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;

/**
 * Starting an instalment plan: a buyer picks an amount per month (and optionally how many months) and is sent to
 * Stripe's hosted page on the STALL's own account.
 *
 * <p>Public, like {@code POST /api/v1/checkout/purchase}: it is reached from the wallet's Buy page by somebody with no
 * session. So everything that could refuse the plan is checked HERE, before the buyer sees a card form. Unlike a
 * one-off purchase, a plan keeps charging, so it is also refused on any account where Imani would bear the Stripe
 * Billing fee, the Connect fees or a negative balance ({@link RecurringEligibility}).
 *
 * <p>The buyer's key is recorded on Imani's own plan row BEFORE Stripe is called, and never read from an event later.
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "stripe", name = {"enabled", "recurring.enabled"}, havingValue = "true")
@RequestMapping("/api/v1/checkout")
public class PlanCheckoutController {

    private static final String HEX_KEY = "^[0-9a-f]{64}$";
    private static final String RETRY_AFTER_SECONDS = "5";

    private final ConnectedStripeAccountRepository accounts;
    private final RecurringPlanRepository plans;
    private final StripeCheckoutService checkout;
    private final StallReadinessClient readiness;
    private final StripeRecurringProperties properties;

    public PlanCheckoutController(ConnectedStripeAccountRepository accounts, RecurringPlanRepository plans,
            StripeCheckoutService checkout, StallReadinessClient readiness, StripeRecurringProperties properties) {
        this.accounts = accounts;
        this.plans = plans;
        this.checkout = checkout;
        this.readiness = readiness;
        this.properties = properties;
    }

    @PostMapping("/plan")
    public ResponseEntity<?> start(@RequestBody PlanRequest request) {
        if (request == null || request.stallPubkey() == null || request.buyerPubkey() == null
                || request.amountMinor() == null) {
            return bad("a stall, a buyer and an amount per instalment are required");
        }
        String stall = request.stallPubkey().trim().toLowerCase();
        String buyer = request.buyerPubkey().trim().toLowerCase();
        if (!stall.matches(HEX_KEY)) {
            return bad("that is not a valid stall");
        }
        if (!buyer.matches(HEX_KEY)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", PurchaseCheckoutController.GUEST_REFUSED));
        }
        if (request.amountMinor() < properties.getMinInstalmentMinor()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "the instalment is below the minimum of " + properties.getMinInstalmentMinor() + " minor units",
                    "code", "BELOW_MINIMUM",
                    "minAmountMinor", properties.getMinInstalmentMinor()));
        }
        Integer instalments = request.instalments();
        if (instalments != null && (instalments < 2 || instalments > properties.getMaxInstalments())) {
            return bad("instalments must be between 2 and " + properties.getMaxInstalments() + ", or left out");
        }

        Optional<ConnectedStripeAccount> found = accounts.findByMerchantPubkey(stall);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "this stall does not take card payments", "code", "NOT_CONNECTED"));
        }
        ConnectedStripeAccount account = found.get();
        if (!account.isChargesEnabled()) {
            return conflict("this stall cannot accept card payments yet");
        }
        String currency = account.getDefaultCurrency();
        if (currency == null || currency.isBlank()) {
            return conflict("this stall has no currency configured");
        }
        Optional<String> ineligible = RecurringEligibility.refusal(account, properties.isRequireControls());
        if (ineligible.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", ineligible.get(), "code", "ACCOUNT_NOT_ELIGIBLE"));
        }

        StallReadinessClient.Readiness ready = readiness.checkSale(stall, currency, request.amountMinor());
        if (ready.state() == StallReadinessClient.State.NOT_READY) {
            if (StallReadinessClient.isSalesLimit(ready.reason())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", PurchaseCheckoutController.salesLimitMessage(ready.reason()), "reason", ready.reason()));
            }
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", StallReadinessClient.NOT_IN_PLAN.equals(ready.reason())
                            ? PurchaseCheckoutController.NOT_IN_PLAN : PurchaseCheckoutController.STALL_NOT_READY,
                    "reason", ready.reason()));
        }
        if (ready.state() != StallReadinessClient.State.READY) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(Map.of("error", PurchaseCheckoutController.CARD_SALES_UNAVAILABLE));
        }

        RecurringPlan plan = new RecurringPlan();
        plan.setId(UUID.randomUUID());
        plan.setConnectedAccountId(account.getStripeAccountId());
        plan.setStallPubkey(stall);
        plan.setBuyerPubkey(buyer);
        plan.setAmountMinor(request.amountMinor());
        plan.setCurrency(currency.toLowerCase());
        plan.setTotalInstalments(instalments);
        plan.setStatus(RecurringPlan.Status.PENDING);
        plan.setLivemode(false);
        plan.setCreatedAt(Instant.now());
        plan.setUpdatedAt(Instant.now());
        plans.save(plan);

        try {
            StripeCheckoutSession session = checkout.createPlanSession(plan.getId().toString(), plan.getAmountMinor(),
                    "Instalment plan", plan.getCurrency(), plan.getConnectedAccountId(), Map.of(),
                    // Always the deployment's own return pages. A caller-supplied URL would let anyone choose where a
                    // buyer lands after paying, which is a phishing page one redirect from a real payment.
                    null, null);
            plan.setCheckoutSessionId(session.getSessionId());
            plan.setLivemode(session.isLivemode());
            plan.setUpdatedAt(Instant.now());
            plans.save(plan);
            return ResponseEntity.ok(new PlanResponse(session.getSessionUrl(), plan.getId().toString()));
        } catch (RuntimeException e) {
            // No session exists, so nothing can charge this buyer. The row is closed, not left PENDING forever.
            log.warn("Could not start plan {} for stall {}: {}", plan.getId(), stall, e.getMessage());
            plan.setStatus(RecurringPlan.Status.CANCELLED);
            plan.setUpdatedAt(Instant.now());
            plans.save(plan);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(Map.of("error", PurchaseCheckoutController.CARD_PAYMENTS_UNAVAILABLE));
        }
    }

    private static ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
    }

    /**
     * @param stallPubkey whose vouchers the instalments buy
     * @param buyerPubkey who receives them; must be a real wallet key (a plan cannot be delivered by claim link)
     * @param amountMinor one instalment
     * @param instalments how many, or null for an open-ended plan
     */
    public record PlanRequest(
            @JsonProperty("stall_pubkey") @JsonAlias("stallPubkey") String stallPubkey,
            @JsonProperty("buyer_pubkey") @JsonAlias("buyerPubkey") String buyerPubkey,
            @JsonProperty("amount_minor") @JsonAlias("amountMinor") Long amountMinor,
            @JsonProperty("instalments") Integer instalments) {}

    /** camelCase, as {@code POST /api/v1/checkout/purchase} answers, so the wallet reads both purchase kinds alike. */
    public record PlanResponse(String checkoutUrl, String planId) {}
}
