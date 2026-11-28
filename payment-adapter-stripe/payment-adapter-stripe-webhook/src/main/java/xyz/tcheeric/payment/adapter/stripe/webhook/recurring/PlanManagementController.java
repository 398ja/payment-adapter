package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier;

/**
 * A buyer's instalment plans, read and cancelled from the wallet itself, and a stall's count of them.
 *
 * <p>Every route is a <b>signed request</b>: a NIP-98 {@code Authorization: Nostr ...} event for the exact URL and
 * verb, against one of {@code purchase.state.public-origins}, as {@code StallPurchasesController} does for a stall's
 * sales. The buyer (or stall) is whoever signed; no key is read from the query or body. A plan that belongs to
 * someone else answers 404, the same as an unknown one, so a plan id cannot be probed.
 *
 * <p>Under {@code /api/v1/checkout}, not {@code /api/v1/purchases}: the latter is the purchase issuer's bearer-token
 * surface, which a browser can never hold.
 *
 * <p>Cancelling ends the subscription AT PERIOD END on the stall's account. Vouchers already issued stay valid, and
 * nothing is refunded: a refund is the stall's decision, made in its own Stripe account.
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "stripe", name = {"enabled", "recurring.enabled"}, havingValue = "true")
@RequestMapping(PlanManagementController.PATH)
public class PlanManagementController {

    public static final String PATH = "/api/v1/checkout/plans";
    private static final String HEX_KEY = "^[0-9a-f]{64}$";

    private final RecurringPlanRepository plans;
    private final StripePurchaseRepository purchases;
    private final StripeSubscriptionClient subscriptions;
    private final SignedCaller signed;

    @Autowired
    public PlanManagementController(RecurringPlanRepository plans, StripePurchaseRepository purchases,
            StripeSubscriptionClient subscriptions,
            @Value("${purchase.state.public-origins:}") List<String> publicOrigins) {
        this(plans, purchases, subscriptions, new Nip98Verifier(), publicOrigins);
    }

    public PlanManagementController(RecurringPlanRepository plans, StripePurchaseRepository purchases,
            StripeSubscriptionClient subscriptions, Nip98Verifier verifier, List<String> publicOrigins) {
        this.plans = plans;
        this.purchases = purchases;
        this.subscriptions = subscriptions;
        this.signed = new SignedCaller(verifier, publicOrigins);
    }

    /** The signer's plans, newest first. PENDING plans are included so the wallet can offer "Try again". */
    @GetMapping
    public ResponseEntity<?> mine(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return withSigner(authorization, PATH, "GET", buyer -> ResponseEntity.ok(
                plans.findTop100ByBuyerPubkeyOrderByCreatedAtDesc(buyer).stream().map(this::view).toList()));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<?> get(@PathVariable String planId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return withSigner(authorization, PATH + "/" + planId, "GET", buyer -> owned(planId, buyer)
                .<ResponseEntity<?>>map(plan -> ResponseEntity.ok(detail(plan)))
                .orElseGet(PlanManagementController::notFound));
    }

    @PostMapping("/{planId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String planId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return withSigner(authorization, PATH + "/" + planId + "/cancel", "POST", buyer -> owned(planId, buyer)
                .map(this::cancel)
                .orElseGet(PlanManagementController::notFound));
    }

    /**
     * {@code GET /api/v1/checkout/plans/summary?stall=<pubkey>}, signed BY that stall: how many people pay it monthly
     * and what that comes to, per currency. Counts {@code ACTIVE} plans only. No buyers, no amounts per plan.
     */
    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(name = "stall", required = false) String stallParam,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String stall = stallParam == null ? "" : stallParam.toLowerCase(Locale.ROOT);
        if (!stall.matches(HEX_KEY)) {
            return ResponseEntity.badRequest().body(Map.of("error", "stall must be 64 hex characters"));
        }
        return withSigner(authorization, PATH + "/summary?stall=" + stallParam, "GET", signer -> {
            if (!signer.equals(stall)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "signed by another key"));
            }
            List<RecurringPlan> active = plans.findByStallPubkeyAndStatusIn(stall, List.of(Status.ACTIVE));
            return ResponseEntity.ok(StallSummary.of(active));
        });
    }

    private ResponseEntity<?> withSigner(String authorization, String pathAndQuery, String method,
            java.util.function.Function<String, ResponseEntity<?>> body) {
        if (!signed.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "monthly payments are not configured"));
        }
        Optional<String> signer = signed.of(authorization, pathAndQuery, method);
        if (signer.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "NIP-98 authorization required"));
        }
        return body.apply(signer.get());
    }

    private ResponseEntity<?> cancel(RecurringPlan plan) {
        if (plan.getStatus() == Status.COMPLETED || plan.getStatus() == Status.CANCELLED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "this plan has already ended"));
        }
        if (plan.getStatus() == Status.ORPHANED) {
            // Imani can no longer act on this subscription. Say so rather than pretend it was cancelled.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "this plan can no longer be managed here; ask the stall to cancel it in Stripe"));
        }
        if (plan.getSubscriptionId() == null) {
            // The buyer never finished Stripe's page: there is no subscription yet. Expire the page first, or the
            // buyer could still finish it and start a subscription on a plan already closed here.
            if (plan.getCheckoutSessionId() != null) {
                try {
                    subscriptions.expireCheckoutSession(plan.getConnectedAccountId(), plan.getCheckoutSessionId());
                } catch (RuntimeException e) {
                    log.warn("Could not expire the checkout of plan {}: {}", plan.getId(), e.getMessage());
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(Map.of("error", "could not reach Stripe to cancel; try again"));
                }
            }
            plan.setStatus(Status.CANCELLED);
        } else if (plan.getCancelRequestedAt() == null) {
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
        UUID id;
        try {
            id = UUID.fromString(planId.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return plans.findById(id).filter(plan -> plan.getBuyerPubkey().equals(buyerPubkey));
    }

    private PlanView view(RecurringPlan plan) {
        return PlanView.of(plan, purchases.countByRecurringPlanId(plan.getId()));
    }

    private PlanDetail detail(RecurringPlan plan) {
        List<String> vouchers = purchases.findByRecurringPlanIdAndVoucherIdIsNotNullOrderByCreatedAtAsc(plan.getId())
                .stream().map(StripePurchase::getVoucherId).toList();
        return new PlanDetail(view(plan), vouchers);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no such plan"));
    }

    /**
     * One plan as the buyer's wallet shows it. camelCase, like the rest of the checkout surface the wallet reads.
     *
     * @param instalmentsPaid instalments recorded so far (the "3 of 6 paid")
     * @param cancelRequested the buyer asked to stop; no further payments will be taken
     */
    public record PlanView(String planId, String stallPubkey, String status, long amountMinor, String currency,
            Integer totalInstalments, long instalmentsPaid, boolean cancelRequested, Instant cancelRequestedAt,
            Instant createdAt) {

        static PlanView of(RecurringPlan plan, long paid) {
            return new PlanView(plan.getId().toString(), plan.getStallPubkey(), plan.getStatus().name(),
                    plan.getAmountMinor(), plan.getCurrency(), plan.getTotalInstalments(), paid,
                    plan.getCancelRequestedAt() != null, plan.getCancelRequestedAt(), plan.getCreatedAt());
        }
    }

    /** A plan and the vouchers its instalments issued, oldest first. */
    public record PlanDetail(PlanView plan, List<String> voucherIds) {}

    /** What a stall sees: how many people pay it monthly, and the monthly total per currency. */
    public record StallSummary(int activePlans, List<CurrencyTotal> monthlyTotals) {

        static StallSummary of(List<RecurringPlan> active) {
            Map<String, Long> byCurrency = new java.util.TreeMap<>();
            active.forEach(plan -> byCurrency.merge(plan.getCurrency(), plan.getAmountMinor(), Long::sum));
            List<CurrencyTotal> totals = byCurrency.entrySet().stream()
                    .map(entry -> new CurrencyTotal(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparing(CurrencyTotal::currency)).toList();
            return new StallSummary(active.size(), totals);
        }
    }

    public record CurrencyTotal(String currency, long amountMinor) {}
}
