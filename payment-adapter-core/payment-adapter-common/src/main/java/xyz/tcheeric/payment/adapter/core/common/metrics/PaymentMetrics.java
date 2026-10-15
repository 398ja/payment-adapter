package xyz.tcheeric.payment.adapter.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one payments metric family (imani-deploy#210), and the only place its labels are spelled.
 *
 * <p>Three series, identical in every service that completes a payment (payment-adapter,
 * gateway-core, merchant-service, purchase-issuer):
 *
 * <ul>
 *   <li>{@code imani_payments_total{source,status,reason,currency}}</li>
 *   <li>{@code imani_payment_amount_minor_total{source,status,currency}}</li>
 *   <li>{@code imani_payment_duration_seconds{source,stage}} (histogram)</li>
 * </ul>
 *
 * <h2>Cardinality</h2>
 *
 * <p>Every label is a closed enum below, or a currency forced to three upper-case letters. No
 * merchant or customer key, amount, session id or {@code pi_} ever reaches a label: those are
 * unbounded, and one series per sale would melt Prometheus and leak who bought what. The worst
 * case is {@code |Source| x |Status| x |Reason| x currencies}, in practice a few dozen series,
 * because most status and reason pairs never occur together.
 *
 * <p>Takes a registry, or falls back to Micrometer's global one, which Spring Boot binds to the
 * Prometheus registry by default. The fallback is what lets classes built by ServiceLoader before
 * Spring exists (the webhook handlers) still report.
 */
public final class PaymentMetrics {

    public static final String PAYMENTS = "imani.payments";
    public static final String AMOUNT = "imani.payment.amount.minor";
    public static final String DURATION = "imani.payment.duration";
    public static final String WEBHOOKS = "imani.payment.webhooks";

    /** Where the money came from. */
    public enum Source { CARD, CASH, LIGHTNING }

    /**
     * What happened to it.
     *
     * <p>{@code REFUSED} is a checkout we declined to create (no money moved), kept apart from
     * {@code FAILED} so a refused QR scan does not drag down the card success rate.
     *
     * <p>Each status is counted by exactly ONE service, so a sum across jobs never double counts:
     * payment-adapter owns card and lightning, initiated to disputed plus card issued/issue_failed;
     * gateway-core owns the deployed cash funnel (its own /api/v1/cash invoice and saga).
     * payment-adapter's cash module also counts {@code source=cash}, but it is not in the deployed
     * payment-adapter-rest image, so the two never run side by side. purchase-issuer reports its
     * attempts on its own {@code imani_purchase_issuer_*} family rather than this one.
     */
    public enum Status {
        INITIATED, REFUSED, SUCCEEDED, FAILED, CANCELLED, REFUNDED, DISPUTED, ISSUED, ISSUE_FAILED, DELIVERED
    }

    /** Why. A fixed set; anything unforeseen is {@code OTHER}, never free text. */
    public enum Reason {
        NONE, CARD_DECLINED, WEBHOOK_SIGNATURE, CHARGES_DISABLED, CURRENCY_MISMATCH, AMOUNT_MISMATCH,
        CURRENCY_MISSING, NOT_FOUND, INVALID_REQUEST, BUYER_REQUIRED, NO_LISTENER, GATEWAY_ERROR,
        TIMEOUT, EXPIRED, CANCELLED_BY_CUSTOMER, CANCELLED_BY_MERCHANT, MINT_FAILED, DELIVERY_FAILED,
        BLOCKED, UNVERIFIABLE, OTHER
    }

    /** Stripe Connect lifecycle events, counted on {@link #CONNECT}. */
    public enum ConnectEvent { ONBOARDING_STARTED, ONBOARDING_COMPLETED, CHARGES_ENABLED_LOST, CHARGES_ENABLED_GAINED }

    public static final String CONNECT = "imani.stripe.connect.events";

    /** Which interval a duration measures. */
    public enum Stage { CHECKOUT_CREATE, WEBHOOK, PAID_TO_ISSUED, INVOICE_TO_CONFIRM, CONFIRM_TO_DELIVERED }

    /** A webhook's fate. */
    public enum WebhookOutcome { RECEIVED, VERIFIED, SIGNATURE_REJECTED, PROCESSED, DUPLICATE, FAILED }

    /** Currency label when none is known. Also the label for pre-registered zero series. */
    public static final String NO_CURRENCY = "NONE";

    private static final Pattern ISO = Pattern.compile("^[A-Z]{3}$");

    private final MeterRegistry registry;

    private static final Logger log = LoggerFactory.getLogger(PaymentMetrics.class);

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry == null ? Metrics.globalRegistry : registry;
    }

    /** Against Micrometer's global registry. */
    public PaymentMetrics() {
        this(null);
    }

    /**
     * Register the zero series an alert or ratio reads, before anything happens.
     *
     * <p>A counter that appears on first use is absent on a quiet service, and an alert over an
     * absent series cannot fire. Registered with {@link #NO_CURRENCY} so sums are unaffected.
     */
    public PaymentMetrics preRegister(Source source, Status... statuses) {
        safely("pre-register", () -> {
            for (Status status : statuses) {
                payments(source, status, Reason.NONE, NO_CURRENCY);
                amountCounter(source, status, NO_CURRENCY);
            }
        });
        return this;
    }

    public PaymentMetrics preRegisterWebhooks(Source source) {
        safely("pre-register webhooks", () -> {
            for (WebhookOutcome outcome : WebhookOutcome.values()) {
                webhookCounter(source, outcome);
            }
        });
        return this;
    }

    /**
     * Metrics never break a payment path (review of gateway-core#161, S-4, applied here too): every
     * recording runs through this, so a failure inside Micrometer is logged at debug and skipped
     * instead of propagating into a webhook, a checkout or a Connect update.
     */
    static void safely(String what, Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.debug("payment metric {} not recorded: {}", what, e.toString());
        }
    }

    public void record(Source source, Status status, Reason reason, String currency) {
        safely("payments", () -> payments(source, status, reason, currency).increment());
    }

    /** Count one payment and add its amount, when the amount is known and positive. */
    public void record(Source source, Status status, Reason reason, String currency, Long amountMinor) {
        record(source, status, reason, currency);
        if (amountMinor != null && amountMinor > 0) {
            safely("amount", () -> amountCounter(source, status, currency).increment(amountMinor));
        }
    }

    public PaymentMetrics preRegisterConnect() {
        safely("pre-register connect", () -> {
            for (ConnectEvent event : ConnectEvent.values()) {
                connectCounter(event);
            }
        });
        return this;
    }

    public void connect(ConnectEvent event) {
        safely("connect", () -> connectCounter(event).increment());
    }

    private Counter connectCounter(ConnectEvent event) {
        return Counter.builder(CONNECT)
                .description("Stripe Connect account lifecycle events")
                .tag("event", label(event))
                .register(registry);
    }

    public void webhook(Source source, WebhookOutcome outcome) {
        safely("webhook", () -> webhookCounter(source, outcome).increment());
    }

    public void duration(Source source, Stage stage, Duration elapsed) {
        if (elapsed == null || elapsed.isNegative()) {
            return;
        }
        safely("duration", () -> timer(source, stage).record(elapsed));
    }

    public Timer timer(Source source, Stage stage) {
        return Timer.builder(DURATION)
                .description("Time a payment spends in one stage, by source")
                .tag("source", label(source))
                .tag("stage", label(stage))
                .publishPercentileHistogram()
                .register(registry);
    }

    /** ISO 4217, upper case, or {@link #NO_CURRENCY}. Never whatever a caller passed. */
    public static String currency(String raw) {
        if (raw == null) {
            return NO_CURRENCY;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if ("SAT".equals(upper) || "SATS".equals(upper)) {
            return "SAT";
        }
        return ISO.matcher(upper).matches() ? upper : NO_CURRENCY;
    }

    public static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private Counter payments(Source source, Status status, Reason reason, String currency) {
        return Counter.builder(PAYMENTS)
                .description("Payments by source, status and a fixed reason")
                .tag("source", label(source))
                .tag("status", label(status))
                .tag("reason", label(reason == null ? Reason.NONE : reason))
                .tag("currency", currency(currency))
                .register(registry);
    }

    private Counter amountCounter(Source source, Status status, String currency) {
        return Counter.builder(AMOUNT)
                .description("Payment amounts in minor units of their currency")
                .tag("source", label(source))
                .tag("status", label(status))
                .tag("currency", currency(currency))
                .register(registry);
    }

    private Counter webhookCounter(Source source, WebhookOutcome outcome) {
        return Counter.builder(WEBHOOKS)
                .description("Payment provider webhooks by outcome")
                .tag("source", label(source))
                .tag("outcome", label(outcome))
                .register(registry);
    }
}
