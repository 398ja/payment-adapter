package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

/**
 * Reading and cancelling an instalment plan.
 *
 * <p>Under {@code /api/v1/purchases}, so the purchase API token guards it: this is for the wallet's backend, which
 * has authenticated the buyer, not for a browser. The buyer's key is still required and must match the plan, so a
 * valid token alone cannot cancel somebody else's plan. A wrong key answers 404, the same as an unknown plan.
 *
 * <p>Cancelling ends the subscription AT PERIOD END on the stall's account. Vouchers already issued stay valid, and
 * nothing is refunded: a refund is the stall's decision, made in its own Stripe account.
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "stripe", name = {"enabled", "recurring.enabled"}, havingValue = "true")
@RequestMapping("/api/v1/purchases/plans")
public class PlanManagementController {

    private final RecurringPlanRepository plans;
    private final StripePurchaseRepository purchases;
    private final StripeSubscriptionClient subscriptions;

    public PlanManagementController(RecurringPlanRepository plans, StripePurchaseRepository purchases,
            StripeSubscriptionClient subscriptions) {
        this.plans = plans;
        this.purchases = purchases;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/{planId}")
    public ResponseEntity<?> get(@PathVariable String planId, @RequestParam("buyer_pubkey") String buyerPubkey) {
        return owned(planId, buyerPubkey).<ResponseEntity<?>>map(plan -> ResponseEntity.ok(view(plan)))
                .orElseGet(PlanManagementController::notFound);
    }

    @PostMapping("/{planId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String planId, @RequestBody CancelRequest request) {
        Optional<RecurringPlan> found = owned(planId, request == null ? null : request.buyerPubkey());
        if (found.isEmpty()) {
            return notFound();
        }
        RecurringPlan plan = found.get();
        if (plan.getStatus() == Status.COMPLETED || plan.getStatus() == Status.CANCELLED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "this plan has already ended"));
        }
        if (plan.getStatus() == Status.ORPHANED) {
            // Imani can no longer act on this subscription. Say so rather than pretend it was cancelled.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "this plan can no longer be managed here; ask the stall to cancel it in Stripe"));
        }
        if (plan.getSubscriptionId() == null) {
            // The buyer never finished Stripe's page: there is no subscription, so nothing can charge them.
            plan.setStatus(Status.CANCELLED);
        } else {
            try {
                subscriptions.cancelAtPeriodEnd(plan.getConnectedAccountId(), plan.getSubscriptionId());
            } catch (RuntimeException e) {
                log.warn("Could not cancel plan {}: {}", plan.getId(), e.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "could not reach Stripe to cancel; try again"));
            }
            plan.setCancelRequestedAt(Instant.now());
        }
        plan.setUpdatedAt(Instant.now());
        plans.save(plan);
        return ResponseEntity.ok(view(plan));
    }

    private Optional<RecurringPlan> owned(String planId, String buyerPubkey) {
        if (planId == null || buyerPubkey == null) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(planId.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return plans.findById(id).filter(plan -> plan.getBuyerPubkey().equals(buyerPubkey.trim().toLowerCase()));
    }

    private PlanView view(RecurringPlan plan) {
        return new PlanView(plan.getId().toString(), plan.getStatus().name(), plan.getAmountMinor(), plan.getCurrency(),
                plan.getTotalInstalments(), purchases.countByRecurringPlanId(plan.getId()),
                plan.getCancelRequestedAt() != null);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no such plan"));
    }

    public record CancelRequest(@JsonProperty("buyer_pubkey") @JsonAlias("buyerPubkey") String buyerPubkey) {}

    public record PlanView(
            @JsonProperty("plan_id") String planId,
            @JsonProperty("status") String status,
            @JsonProperty("amount_minor") long amountMinor,
            @JsonProperty("currency") String currency,
            @JsonProperty("total_instalments") Integer totalInstalments,
            @JsonProperty("instalments_paid") long instalmentsPaid,
            @JsonProperty("cancel_requested") boolean cancelRequested) {}
}
