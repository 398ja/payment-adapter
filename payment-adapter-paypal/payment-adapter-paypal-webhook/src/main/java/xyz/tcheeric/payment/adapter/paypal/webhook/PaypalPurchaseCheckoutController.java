package xyz.tcheeric.payment.adapter.paypal.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Reason;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.PaypalCheckout;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.config.PaypalGatewayProperties;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalCheckoutCreationException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalCheckoutService;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.Outcome;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseCheckoutController;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseCheckoutController.PurchaseRequest;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseCheckoutController.PurchaseResponse;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;

/**
 * Starting a card purchase on PayPal: {@code PurchaseCheckoutController} for a stall that takes
 * cards through its own PayPal account (ADR 0002). The same body, the same refusals in the same
 * order and with the same words, the same answer: somewhere to pay, and a reference.
 *
 * <p>Public, for the reason Stripe's is: a buyer arrives from a stall's QR with no session. It
 * creates an order paying the stall and nothing else.
 *
 * <p>What a Stripe session carries in its metadata (the buyer, the deployment) is written to
 * {@code paypal_checkout} before the buyer is sent to PayPal, and read back from there when the
 * order is paid, so nothing the purchase depends on ever crosses PayPal.
 *
 * <p>Also the buyer's way back ({@code GET /return}): PayPal sends an approving buyer here, the
 * order is captured on the spot, and the buyer goes on to the success page.
 */
@Slf4j
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "paypal", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/checkout/paypal")
public class PaypalPurchaseCheckoutController {

    private final ConnectedPaypalAccountRepository accounts;
    private final PaypalCheckoutRepository checkouts;
    private final PaypalCheckoutService checkout;
    private final PaypalCaptureService captures;
    private final StallReadinessClient readiness;
    private final ObjectMapper objectMapper;
    private final long maxAmountMinor;

    private PaymentMetrics metrics = new PaymentMetrics();

    /**
     * @param maxAmountMinor the same public ceiling as Stripe's checkout ({@code purchase.max-amount-minor})
     */
    public PaypalPurchaseCheckoutController(ConnectedPaypalAccountRepository accounts,
            PaypalCheckoutRepository checkouts, PaypalCheckoutService checkout, PaypalCaptureService captures,
            StallReadinessClient readiness, ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${purchase.max-amount-minor:1000000}")
            long maxAmountMinor) {
        this.accounts = accounts;
        this.checkouts = checkouts;
        this.checkout = checkout;
        this.captures = captures;
        this.readiness = readiness;
        this.objectMapper = objectMapper;
        this.maxAmountMinor = maxAmountMinor;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry registry) {
        this.metrics = new PaymentMetrics(registry).preRegister(Source.CARD, Status.INITIATED, Status.REFUSED);
    }

    private ResponseEntity<?> refused(Reason reason, String currency, ResponseEntity<?> response) {
        metrics.record(Source.CARD, Status.REFUSED, reason, currency);
        return response;
    }

    @PostMapping("/purchase")
    public ResponseEntity<?> start(@RequestBody PurchaseRequest request) {
        // Gifts are drafts priced and paid through Stripe (imani-wallet#410); not offered on PayPal yet.
        if (request != null && request.draftId() != null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("gifting is not available here"));
        }
        if (request == null || request.stallPubkey() == null || request.amountMinor() == null) {
            return refused(Reason.INVALID_REQUEST, null,
                    ResponseEntity.badRequest().body(error("a stall and an amount are required")));
        }
        if (request.amountMinor() <= 0) {
            return refused(Reason.INVALID_REQUEST, null,
                    ResponseEntity.badRequest().body(error("the amount must be more than zero")));
        }
        if (request.amountMinor() > maxAmountMinor) {
            return refused(Reason.INVALID_REQUEST, null,
                    ResponseEntity.badRequest().body(error("that amount is too large for one voucher")));
        }
        String stall = request.stallPubkey().trim().toLowerCase();
        if (!stall.matches("^[0-9a-f]{64}$")) {
            return refused(Reason.INVALID_REQUEST, null,
                    ResponseEntity.badRequest().body(error("that is not a stall address")));
        }
        String buyer = normalisedBuyer(request.buyerPubkey());
        if (buyer == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(error(PurchaseCheckoutController.GUEST_REFUSED));
        }

        Optional<ConnectedPaypalAccount> found = accounts.findByMerchantPubkey(stall);
        if (found.isEmpty() || found.get().getPaypalMerchantId() == null) {
            return refused(Reason.NOT_FOUND, null, ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("this stall does not take card payments")));
        }
        ConnectedPaypalAccount account = found.get();
        // Set only by PaypalCardCapability: receivable, email confirmed, and our PAYMENT permission.
        if (!account.isChargesEnabled()) {
            return refused(Reason.CHARGES_DISABLED, account.getDefaultCurrency(), ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("this stall cannot accept card payments yet")));
        }
        String currency = account.getDefaultCurrency();
        if (currency == null || currency.isBlank()) {
            return refused(Reason.CURRENCY_MISSING, null, ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("this stall has no currency configured")));
        }

        // payment-adapter#293, unchanged: can a coupon be issued for THIS sale? Unknown fails closed.
        StallReadinessClient.Readiness ready = readiness.checkSale(stall, currency, request.amountMinor());
        if (ready.state() == StallReadinessClient.State.NOT_READY) {
            if (StallReadinessClient.isSalesLimit(ready.reason())) {
                return refused(Reason.SALES_LIMIT, currency, ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", PurchaseCheckoutController.salesLimitMessage(ready.reason()),
                                "reason", ready.reason())));
            }
            if (StallReadinessClient.NOT_IN_PLAN.equals(ready.reason())) {
                return refused(Reason.NOT_IN_PLAN, currency, ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", PurchaseCheckoutController.NOT_IN_PLAN, "reason", ready.reason())));
            }
            return refused(Reason.NOT_READY, currency, ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", PurchaseCheckoutController.STALL_NOT_READY, "reason", ready.reason())));
        }
        if (ready.state() != StallReadinessClient.State.READY) {
            return refused(Reason.READINESS_UNKNOWN, currency, ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, PurchaseCheckoutController.RETRY_AFTER_SECONDS)
                    .body(error(PurchaseCheckoutController.CARD_SALES_UNAVAILABLE)));
        }

        String quoteId = UUID.randomUUID().toString();
        PaypalOrder order;
        try {
            // No platform fee, for the reason Stripe's checkout gives: a commercial decision, not a constant.
            order = checkout.createPurchaseOrder(quoteId, request.amountMinor(), "Voucher", currency,
                    account.getPaypalMerchantId(), null, null, null);
        } catch (PaypalCheckoutCreationException e) {
            if (!e.isPaypalUnavailable()) {
                return gatewayRefused(stall, currency, e);
            }
            log.warn("PayPal unavailable starting a purchase for stall {}: {}", stall, e.getMessage());
            return refused(Reason.GATEWAY_ERROR, currency, ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, PurchaseCheckoutController.RETRY_AFTER_SECONDS)
                    .body(error(PurchaseCheckoutController.CARD_PAYMENTS_UNAVAILABLE)));
        } catch (RuntimeException e) {
            return gatewayRefused(stall, currency, e);
        }

        // Before the buyer can approve: the webhook and the reconciler find the purchase here.
        PaypalCheckout row = new PaypalCheckout();
        row.setOrderId(order.id());
        row.setQuoteId(quoteId);
        row.setStallPubkey(stall);
        row.setPaypalMerchantId(account.getPaypalMerchantId());
        row.setAmountMinor(request.amountMinor());
        row.setCurrency(checkout.properties().normalizeCurrency(currency));
        row.setEnvironment(checkout.properties().resolvedEnvironment());
        row.setMetadata(metadata(buyer, checkout.properties().resolvedEnvironment()));
        row.setLivemode(checkout.properties().isLive());
        row.setStatus(PaypalCheckout.Status.CREATED);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        checkouts.save(row);

        metrics.record(Source.CARD, Status.INITIATED, Reason.NONE, currency, request.amountMinor());
        return ResponseEntity.ok(new PurchaseResponse(order.approveUrl(), quoteId));
    }

    /**
     * Where PayPal returns an approving buyer ({@code ?token=<order id>}). Captured now, then on to
     * the success page. Never an error page for the buyer: whatever happens here, the webhook and the
     * reconciler capture the same order with the same request id, so the purchase still completes.
     */
    @GetMapping("/return")
    public ResponseEntity<Void> returned(@RequestParam(name = "token", required = false) String orderId) {
        PaypalGatewayProperties properties = checkout.properties();
        if (orderId == null || !orderId.matches("^[A-Z0-9]{1,64}$")) {
            return redirect(properties.getCancelUrl(), null);
        }
        Optional<PaypalCheckout> row = checkouts.findByOrderId(orderId);
        if (row.isEmpty()) {
            return redirect(properties.getCancelUrl(), null);
        }
        Outcome outcome;
        try {
            outcome = captures.capture(row.get(), false);
        } catch (RuntimeException e) {
            log.warn("PayPal order {} not recorded on the buyer's return; the webhook or reconciler will: {}",
                    orderId, e.getMessage());
            outcome = Outcome.UNAVAILABLE;
        }
        if (outcome == Outcome.DECLINED) {
            return redirect(properties.getCancelUrl(), orderId);
        }
        return redirect(properties.getSuccessUrl(), orderId);
    }

    private static ResponseEntity<Void> redirect(String target, String orderId) {
        if (target == null || target.isBlank()) {
            return ResponseEntity.noContent().build();
        }
        String url = orderId == null ? target.replace(PaypalGatewayProperties.ORDER_ID_PLACEHOLDER, "")
                : target.replace(PaypalGatewayProperties.ORDER_ID_PLACEHOLDER, orderId);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(url)).build();
    }

    private ResponseEntity<?> gatewayRefused(String stall, String currency, RuntimeException e) {
        log.warn("Could not start a PayPal purchase for stall {}: {}", stall, e.getMessage());
        return refused(Reason.GATEWAY_ERROR, currency, ResponseEntity.badRequest().body(error(e.getMessage())));
    }

    private String metadata(String buyer, String environment) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("buyer_pubkey", buyer);
        // After the buyer, as Stripe's: the deployment's name, never the caller's.
        if (environment != null) {
            metadata.put(StripeGatewayProperties.ENVIRONMENT_METADATA_KEY, environment);
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalisedBuyer(String buyerPubkey) {
        if (buyerPubkey == null) {
            return null;
        }
        String trimmed = buyerPubkey.trim().toLowerCase();
        return trimmed.matches("^[0-9a-f]{64}$") ? trimmed : null;
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message == null ? "unavailable" : message);
    }
}
