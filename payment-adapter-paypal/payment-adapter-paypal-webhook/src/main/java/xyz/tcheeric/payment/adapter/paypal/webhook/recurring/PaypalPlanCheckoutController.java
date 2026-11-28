package xyz.tcheeric.payment.adapter.paypal.webhook.recurring;

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
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StallInstalmentSettings;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StallInstalmentSettings.Interval;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StallInstalmentSettingsRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSubscription;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionRequest;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionsClient;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseCheckoutController;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;

/**
 * {@code POST /api/v1/checkout/paypal/plan}: Stripe's {@code POST /api/v1/checkout/plan} for a stall that takes cards
 * through PayPal (ADR 0003). Same body, same refusals and codes, same response, so the wallet treats both alike.
 *
 * <p>The plan row (buyer, stall, amount, term) is written BEFORE PayPal is called. Then the product, billing plan and
 * subscription are created on the stall's own PayPal account, and the buyer is sent to PayPal's approval page. Unlike
 * Stripe Checkout, the subscription id is known at once, so every later event is matched to the plan by it.
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "paypal", name = {"enabled", "recurring.enabled"}, havingValue = "true")
@RequestMapping("/api/v1/checkout/paypal")
public class PaypalPlanCheckoutController {

    private static final String HEX_KEY = "^[0-9a-f]{64}$";
    private static final String RETRY_AFTER_SECONDS = "5";

    private final ConnectedPaypalAccountRepository accounts;
    private final RecurringPlanRepository plans;
    private final PaypalSubscriptionsClient subscriptions;
    private final StallReadinessClient readiness;
    private final PaypalRecurringProperties properties;
    private final PaypalGatewayProperties paypal;
    private final StallInstalmentSettingsRepository settings;

    public PaypalPlanCheckoutController(ConnectedPaypalAccountRepository accounts, RecurringPlanRepository plans,
            PaypalSubscriptionsClient subscriptions, StallReadinessClient readiness,
            PaypalRecurringProperties properties, PaypalGatewayProperties paypal,
            StallInstalmentSettingsRepository settings) {
        this.accounts = accounts;
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.readiness = readiness;
        this.properties = properties;
        this.paypal = paypal;
        this.settings = settings;
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
        Optional<Interval> parsed = request.interval() == null ? Optional.of(Interval.MONTH)
                : Interval.parse(request.interval());
        if (parsed.isEmpty()) {
            return bad("interval must be week, month or year");
        }
        Interval interval = parsed.get();
        int max = properties.maxInstalmentsFor(interval.stripe());
        Integer instalments = request.instalments();
        if (instalments != null && (instalments < 2 || instalments > max)) {
            return bad("instalments must be between 2 and " + max + ", or left out");
        }

        Optional<ConnectedPaypalAccount> found = accounts.findByMerchantPubkey(stall);
        if (found.isEmpty() || found.get().getPaypalMerchantId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "this stall does not take card payments", "code", "NOT_CONNECTED"));
        }
        ConnectedPaypalAccount account = found.get();
        if (!account.isChargesEnabled()) {
            return conflict("this stall cannot accept card payments yet");
        }
        String currency = account.getDefaultCurrency();
        if (currency == null || currency.isBlank()) {
            return conflict("this stall has no currency configured");
        }
        if (!paypal.isCurrencyAllowed(currency)) {
            return conflict("this stall's currency is not accepted here");
        }

        Optional<StallInstalmentSettings> offer = settings.findById(stall);
        if (offer.isEmpty() || !offer.get().isEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "this stall does not offer instalment plans", "code", "NOT_OFFERED"));
        }
        if (!offer.get().offers(interval)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "this stall does not offer " + interval.adjective() + " payments",
                    "code", "INTERVAL_NOT_OFFERED",
                    "intervals", offer.get().intervalNames()));
        }

        ResponseEntity<?> notReady = readinessRefusal(stall, currency, request.amountMinor());
        if (notReady != null) {
            return notReady;
        }

        RecurringPlan plan = pendingPlan(account, stall, buyer, request.amountMinor(), currency, interval, instalments);
        plans.save(plan);
        return startSubscription(plan, interval);
    }

    private ResponseEntity<?> readinessRefusal(String stall, String currency, long amountMinor) {
        StallReadinessClient.Readiness ready = readiness.checkSale(stall, currency, amountMinor);
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
        return null;
    }

    private static RecurringPlan pendingPlan(ConnectedPaypalAccount account, String stall, String buyer, long amount,
            String currency, Interval interval, Integer instalments) {
        RecurringPlan plan = new RecurringPlan();
        plan.setId(UUID.randomUUID());
        plan.setProcessor(RecurringPlan.PROCESSOR_PAYPAL);
        plan.setConnectedAccountId(account.getPaypalMerchantId());
        plan.setStallPubkey(stall);
        plan.setBuyerPubkey(buyer);
        plan.setAmountMinor(amount);
        plan.setCurrency(currency.toLowerCase());
        plan.setIntervalUnit(interval.stripe());
        plan.setTotalInstalments(instalments);
        plan.setStatus(RecurringPlan.Status.PENDING);
        plan.setCreatedAt(Instant.now());
        plan.setUpdatedAt(Instant.now());
        return plan;
    }

    private ResponseEntity<?> startSubscription(RecurringPlan plan, Interval interval) {
        try {
            PaypalSubscription subscription = subscriptions.startSubscription(new PaypalSubscriptionRequest(
                    plan.getId().toString(), plan.getConnectedAccountId(), plan.getAmountMinor(), plan.getCurrency(),
                    interval.stripe(), plan.getTotalInstalments(), customId(plan), properties.getDescription(),
                    paypal.getBrandName(),
                    // Always the deployment's own pages, never a caller's: see PlanCheckoutController.
                    withoutOrderPlaceholder(paypal.getSuccessUrl()), withoutOrderPlaceholder(paypal.getCancelUrl())));
            plan.setSubscriptionId(subscription.id());
            plan.setLivemode(paypal.isLive());
            plan.setUpdatedAt(Instant.now());
            plans.save(plan);
            if (subscription.approveUrl() == null) {
                throw new IllegalStateException("PayPal returned no approval link for subscription " + subscription.id());
            }
            return ResponseEntity.ok(new PlanResponse(subscription.approveUrl(), plan.getId().toString()));
        } catch (RuntimeException e) {
            log.warn("Could not start PayPal plan {} for stall {}: {}", plan.getId(), plan.getStallPubkey(), e.getMessage());
            closeUnstarted(plan);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(Map.of("error", PurchaseCheckoutController.CARD_PAYMENTS_UNAVAILABLE));
        }
    }

    /**
     * Nothing the buyer approved can charge them. A subscription that was created anyway is cancelled, so a buyer who
     * found the link some other way could not start one on a plan closed here; if that fails, the reconciler's
     * abandon rule cancels it later.
     */
    private void closeUnstarted(RecurringPlan plan) {
        if (plan.getSubscriptionId() != null) {
            try {
                subscriptions.cancelSubscription(plan.getConnectedAccountId(), plan.getSubscriptionId(),
                        "Plan could not be started");
            } catch (RuntimeException e) {
                log.warn("Could not cancel unstarted PayPal subscription {}: {}", plan.getSubscriptionId(), e.getMessage());
                return;
            }
        }
        plan.setStatus(RecurringPlan.Status.CANCELLED);
        plan.setUpdatedAt(Instant.now());
        plans.save(plan);
    }

    private String customId(RecurringPlan plan) {
        String environment = paypal.resolvedEnvironment();
        return (environment == null ? "" : environment) + ":" + plan.getId();
    }

    private static String withoutOrderPlaceholder(String url) {
        return url == null ? null : url.replace(PaypalGatewayProperties.ORDER_ID_PLACEHOLDER, "");
    }

    private static ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
    }

    /** The same body as Stripe's plan start. */
    public record PlanRequest(
            @JsonProperty("stall_pubkey")
            @JsonAlias("stallPubkey") String stallPubkey,
            @JsonProperty("buyer_pubkey")
            @JsonAlias("buyerPubkey") String buyerPubkey,
            @JsonProperty("amount_minor")
            @JsonAlias("amountMinor") Long amountMinor,
            @JsonProperty("instalments") Integer instalments,
            @JsonProperty("interval") String interval) {}

    /** The same response as Stripe's plan start. */
    public record PlanResponse(String checkoutUrl, String planId) {}
}
