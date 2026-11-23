package xyz.tcheeric.payment.adapter.paypal.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.PaypalCheckout;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalCheckoutService;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.LiveFacts;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.PaymentStateReader;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileException;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.PaidPurchase;

/**
 * Turns an approved PayPal order into a recorded debt (ADR 0002).
 *
 * <p>A Stripe Checkout Session captures itself; a PayPal order waits, approved, until someone
 * captures it. Three callers may be first: {@code CHECKOUT.ORDER.APPROVED}, the buyer's return to
 * {@code /api/v1/checkout/paypal/return}, and the reconciler. All three come here, and all three
 * capture with the same {@code PayPal-Request-Id}, so PayPal moves the money once and answers every
 * caller with that one capture.
 *
 * <p>Paid means owed, recorded through the same {@link StripePurchaseListener} a Stripe purchase
 * uses, under one event id per capture ({@code paypal:<capture>}): whichever of the webhook, the
 * return and the reconciler records first, the others are redeliveries.
 *
 * <p>The purchase's terms are this service's own {@link PaypalCheckout} row, never anything that
 * crossed PayPal: the buyer, the stall and the deployment were written before the buyer saw PayPal.
 */
@Slf4j
public class PaypalCaptureService {

    /** One event id per capture, whoever records it. */
    public static final String EVENT_PREFIX = "paypal:";

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final PaypalCheckoutRepository checkouts;
    private final ConnectedPaypalAccountRepository accounts;
    private final PaypalOrdersClient orders;
    private final StripePurchaseListener listener;
    private final PaymentStateReader reader;
    private final ObjectMapper objectMapper;

    public PaypalCaptureService(PaypalCheckoutRepository checkouts, ConnectedPaypalAccountRepository accounts,
            PaypalOrdersClient orders, StripePurchaseListener listener, PaymentStateReader reader,
            ObjectMapper objectMapper) {
        this.checkouts = checkouts;
        this.accounts = accounts;
        this.orders = orders;
        this.listener = listener;
        this.reader = reader;
        this.objectMapper = objectMapper;
    }

    /** What became of one attempt. */
    public enum Outcome {
        /** The money moved and the debt is recorded (now, or already). */
        RECORDED,
        /** Captured, but PayPal holds the money for review: {@code PAYMENT.CAPTURE.COMPLETED} follows. */
        PENDING,
        /** The buyer has not approved yet, or must approve again. */
        NOT_APPROVED,
        /** PayPal refused the payment. Nothing owed; the checkout is closed when that is final. */
        DECLINED,
        /** PayPal could not be reached: try again shortly. Nothing changed. */
        UNAVAILABLE
    }

    public static String eventId(String captureId) {
        return EVENT_PREFIX + captureId;
    }

    /**
     * Capture an order the buyer approved, then record it.
     *
     * @param late true from the reconciler: the order may have been refunded or disputed before any
     *             row existed for those webhooks to land on, so its live state is applied as well
     * @throws PaypalCaptureException when the debt could not be recorded; the caller must not
     *                                report success
     */
    public Outcome capture(PaypalCheckout checkout, boolean late) {
        if (checkout.getStatus() == PaypalCheckout.Status.CAPTURED) {
            return Outcome.RECORDED;
        }
        if (checkout.getStatus() == PaypalCheckout.Status.FAILED) {
            return Outcome.DECLINED;
        }
        PaypalOrder order;
        try {
            order = orders.captureOrder(checkout.getPaypalMerchantId(), checkout.getOrderId(),
                    PaypalCheckoutService.captureRequestId(checkout.getOrderId()));
        } catch (PaypalApiException e) {
            if (e.hasIssue("ORDER_ALREADY_CAPTURED") || e.hasIssue("DUPLICATE_INVOICE_ID")) {
                // Someone else captured first. Read what they captured.
                try {
                    order = orders.getOrder(checkout.getPaypalMerchantId(), checkout.getOrderId());
                } catch (PaypalApiException read) {
                    return read.isUnavailable() ? Outcome.UNAVAILABLE : failedRead(checkout, read);
                }
            } else if (e.hasIssue("ORDER_NOT_APPROVED") || e.hasIssue("PAYER_ACTION_REQUIRED")) {
                return Outcome.NOT_APPROVED;
            } else if (e.hasIssue("INSTRUMENT_DECLINED")) {
                // PayPal's word for "send the buyer back to choose another way to pay": the order
                // stays open, so a second card can still settle it.
                note(checkout, "capture declined: INSTRUMENT_DECLINED");
                return Outcome.DECLINED;
            } else if (e.isUnavailable()) {
                return Outcome.UNAVAILABLE;
            } else {
                return failedRead(checkout, e);
            }
        }
        return settle(checkout, order, late, false);
    }

    /** The order as PayPal holds it now: record its capture, close it, or leave it to the buyer. */
    public Outcome settle(PaypalCheckout checkout, PaypalOrder order, boolean late) {
        return settle(checkout, order, late, true);
    }

    /** @param mayCapture false when this order IS a capture's answer: never capture again from here */
    private Outcome settle(PaypalCheckout checkout, PaypalOrder order, boolean late, boolean mayCapture) {
        PaypalCapture capture = order.capture();
        if (capture == null) {
            if (order.voided()) {
                close(checkout, "order voided");
                return Outcome.DECLINED;
            }
            return order.approved() && mayCapture ? capture(checkout, late) : Outcome.NOT_APPROVED;
        }
        return settleCapture(checkout, capture, late);
    }

    /** One capture of a checkout of ours, from a capture response, a webhook or a read. */
    public Outcome settleCapture(PaypalCheckout checkout, PaypalCapture capture, boolean late) {
        if (capture.succeeded()) {
            record(checkout, capture, late || !capture.completed());
            return Outcome.RECORDED;
        }
        if (capture.failed()) {
            close(checkout, "capture " + capture.status());
            return Outcome.DECLINED;
        }
        // PENDING: PayPal holds it (an eCheck, a review). Not owed until it completes.
        if (checkout.getCaptureId() == null && capture.id() != null) {
            checkout.setCaptureId(capture.id());
            save(checkout);
        }
        return Outcome.PENDING;
    }

    /**
     * A completed capture with no checkout row of ours: an order this service did not create, or
     * whose row is lost. Recorded, because a real payment is never dropped, but without the
     * environment stamp, so a deployment that names itself parks it for a person
     * ({@code unstamped_session}) rather than listing it owed to nobody. Only for a payee that is
     * one of our sellers; anything else is not this platform's money.
     *
     * @return whether it was recorded
     */
    public boolean recordOrphan(PaypalCapture capture, boolean livemode) {
        if (capture.payeeMerchantId() == null || capture.id() == null) {
            return false;
        }
        var seller = accounts.findByPaypalMerchantId(capture.payeeMerchantId());
        if (seller.isEmpty()) {
            return false;
        }
        log.warn("[alert] PayPal capture {} for order {} has no checkout row here; recording it for a person",
                capture.id(), capture.orderId());
        paid(new PaidPurchase(eventId(capture.id()),
                capture.orderId() == null ? capture.id() : capture.orderId(), capture.id(),
                capture.payeeMerchantId(), capture.amountMinor(), capture.currency(), Map.of(), livemode,
                StripePurchase.PROCESSOR_PAYPAL, seller.get().getMerchantPubkey()));
        return true;
    }

    private void record(PaypalCheckout checkout, PaypalCapture capture, boolean applyLiveState) {
        if (!Objects.equals(capture.amountMinor(), checkout.getAmountMinor())
                || !checkout.getCurrency().equalsIgnoreCase(String.valueOf(capture.currency()))) {
            // Impossible for an order this service priced, short of tampering on PayPal's side.
            // Recorded as PayPal reports it: the debt is what the buyer actually paid.
            log.error("[alert] PayPal capture {} of order {} is {} {}, but the order was created for {} {}",
                    capture.id(), checkout.getOrderId(), capture.amountMinor(), capture.currency(),
                    checkout.getAmountMinor(), checkout.getCurrency());
        }
        String payee = checkout.getPaypalMerchantId();
        String stall = checkout.getStallPubkey();
        if (capture.payeeMerchantId() != null && !capture.payeeMerchantId().equals(payee)) {
            log.error("[alert] PayPal capture {} of order {} paid {}, not the stall's {}", capture.id(),
                    checkout.getOrderId(), capture.payeeMerchantId(), payee);
            payee = capture.payeeMerchantId();
            stall = accounts.findByPaypalMerchantId(payee).map(a -> a.getMerchantPubkey()).orElse(null);
        }
        paid(new PaidPurchase(eventId(capture.id()), checkout.getOrderId(), capture.id(), payee,
                capture.amountMinor() == null ? checkout.getAmountMinor() : capture.amountMinor(),
                capture.currency() == null ? checkout.getCurrency() : capture.currency(),
                metadata(checkout), checkout.isLivemode(), StripePurchase.PROCESSOR_PAYPAL, stall));

        if (applyLiveState) {
            // A refund or dispute that happened before this row existed was acknowledged as
            // unknown when it arrived. Read it now and land it the way the webhook would have.
            try {
                PaymentState live = reader.paymentState(payee, capture.id());
                if (!live.intact()) {
                    LiveFacts.record(listener, eventId(capture.id()), payee, capture.id(), live);
                }
            } catch (StripeReconcileException e) {
                log.warn("PayPal capture {} recorded; its refund/dispute state could not be read yet: {}",
                        capture.id(), e.getMessage());
            }
        }

        checkout.setStatus(PaypalCheckout.Status.CAPTURED);
        checkout.setCaptureId(capture.id());
        checkout.setLastError(null);
        save(checkout);
    }

    private void paid(PaidPurchase purchase) {
        try {
            listener.onPurchasePaid(purchase);
        } catch (Exception e) {
            throw new PaypalCaptureException("Purchase listener refused PayPal capture " + purchase.paymentIntentId(), e);
        }
    }

    private Outcome failedRead(PaypalCheckout checkout, PaypalApiException e) {
        if (e.isNotFound()) {
            close(checkout, "order not found on PayPal");
            return Outcome.DECLINED;
        }
        // Any other refusal (a restricted payee, a currency the seller cannot take) is final for
        // this order: nothing was captured, so nothing is owed.
        close(checkout, "capture refused: " + (e.issue() == null ? e.status() : e.issue()));
        return Outcome.DECLINED;
    }

    /** An order nobody paid and PayPal no longer holds open: closed, nothing owed. */
    public void abandon(PaypalCheckout checkout, String why) {
        if (checkout.getStatus() == PaypalCheckout.Status.CREATED) {
            close(checkout, why);
        }
    }

    private void close(PaypalCheckout checkout, String why) {
        checkout.setStatus(PaypalCheckout.Status.FAILED);
        checkout.setLastError(abbreviate(why));
        save(checkout);
        log.info("PayPal order {} closed: {}", checkout.getOrderId(), why);
    }

    private void note(PaypalCheckout checkout, String why) {
        checkout.setLastError(abbreviate(why));
        save(checkout);
    }

    /** Losing the race to another caller is fine when the winner wrote the same outcome. */
    private void save(PaypalCheckout checkout) {
        checkout.setUpdatedAt(Instant.now());
        try {
            checkouts.save(checkout);
        } catch (OptimisticLockingFailureException e) {
            PaypalCheckout current = checkouts.findByOrderId(checkout.getOrderId()).orElse(null);
            if (current == null || current.getStatus() == PaypalCheckout.Status.CREATED) {
                throw e;
            }
            log.debug("PayPal order {} already moved to {} by another caller", checkout.getOrderId(),
                    current.getStatus());
        }
    }

    /** The session-metadata equivalent written at checkout: the buyer and the deployment. */
    Map<String, String> metadata(PaypalCheckout checkout) {
        Map<String, String> out = new LinkedHashMap<>();
        if (checkout.getMetadata() != null && !checkout.getMetadata().isBlank()) {
            try {
                out.putAll(objectMapper.readValue(checkout.getMetadata(), STRING_MAP));
            } catch (JsonProcessingException e) {
                log.error("PayPal checkout {} metadata is unreadable; recorded without it", checkout.getOrderId());
            }
        }
        return Map.copyOf(out);
    }

    private static String abbreviate(String text) {
        return text == null || text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    /** The debt could not be recorded. */
    public static class PaypalCaptureException extends RuntimeException {
        public PaypalCaptureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
