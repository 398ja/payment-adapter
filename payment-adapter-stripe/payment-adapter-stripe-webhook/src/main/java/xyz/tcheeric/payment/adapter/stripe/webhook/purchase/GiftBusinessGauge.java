package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.payment.adapter.core.model.entity.gifting.PurchaseDraft;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.GiftOutcome;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

/**
 * Business metrics for gifts: {@code imani_product_items} and {@code imani_product_value} with
 * {@code product="gift"} (business-metrics contract, 2026-11-05).
 *
 * <p><b>Single owner.</b> payment-adapter counts every gift state. The purchase issuer's gift
 * tracker, which alone sees what the recipient did, reports each outcome here
 * ({@code POST /api/v1/purchases/gifts/{draftId}/outcome}, stored on the row by V21) instead of
 * exposing its own series. Two sources under one family would need every dashboard to know which
 * states come from where, and "lifetime issued = sum over all states" would count a gift twice as
 * soon as both sides held a row for it (the tracker forgets a gift once it is finished, so a
 * disjoint split would also lose claimed gifts on the issuer side). One row per gift, one state.
 *
 * <p>A gift is a {@code stripe_purchase} with {@code delivery_kind} {@code handle} or {@code email}
 * ({@code self} is the buyer's own voucher, not a gift; null is a wallet purchase). An unpaid
 * {@code purchase_draft} is not counted: nothing was paid or handed to anyone yet.
 *
 * <p>State mapping (status, gift outcome to contract state):
 * <ul>
 *   <li>REFUNDED: {@code returned}. The buyer has the money back.</li>
 *   <li>OWED, ISSUING, ISSUED, BLOCKED, NEEDS_ATTENTION, DISCHARGED: {@code outstanding} until an
 *       outcome is reported. Paid, and the value is still owed to or held for the recipient.</li>
 *   <li>An outcome, once reported, decides the state whatever the non-refunded status (a report
 *       proves delivery even where the discharge is still unconfirmed):</li>
 *   <li>PARTLY_USED (code): item {@code outstanding}; value split, the remaining
 *       balance in {@code outstanding} and the spent part in {@code redeemed}.</li>
 *   <li>CLAIMED: {@code redeemed} (voucher claimed, or code spent in full).</li>
 *   <li>EXPIRED: {@code expired} (lapsed, not taken back). A code's spent part, if
 *       any, stays in {@code redeemed}.</li>
 *   <li>RETURNED: {@code returned} (taken back by the buyer).</li>
 * </ul>
 * {@code cancelled} is always zero for gifts: nothing withdraws a paid gift before use except a
 * refund, which is {@code returned}.
 *
 * <p>Labels: {@code kind} {@code voucher|code} ({@code gift_kind} null is a voucher),
 * {@code delivery} {@code handle|email}, {@code unit} the upper-case currency (at most 40, then
 * {@code OTHER}). Value is major units of the face currency (ISO 4217 minor digits).
 *
 * <p>A code gift's balance is also counted by imani-checkout-codes under
 * {@code product="checkout_code"}: that is the CODE view, this is the PURCHASE view. A dashboard
 * that totals both must filter on {@code product}, never sum across it.
 *
 * <p>Re-derived by one grouped read-only query per poll, like {@link PaidUnissuedGauge}. Bound
 * eagerly so a zero is a real zero; a failed poll keeps the last values and increments
 * {@code imani_business_metrics_poll_failures_total{source="payment-adapter"}}.
 */
@Slf4j
public class GiftBusinessGauge {

    static final String ITEMS = "imani_product_items";
    static final String VALUE = "imani_product_value";
    static final String POLL_FAILURES = "imani.business.metrics.poll.failures";
    static final String LAST_POLL = "imani_business_metrics_last_poll_seconds";
    static final String SOURCE = "payment-adapter";

    static final List<String> STATES = List.of("outstanding", "redeemed", "returned", "expired", "cancelled");
    static final List<String> KINDS = List.of("voucher", "code");
    static final List<String> DELIVERIES = List.of(PurchaseDraft.EMAIL, PurchaseDraft.HANDLE);
    static final int MAX_UNITS = 40;
    static final String OTHER = "OTHER";
    /** Bound before any sale so the dashboard sees zeros, not absent series. */
    static final String DEFAULT_UNIT = "GBP";

    private final StripePurchaseRepository purchases;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Counter failures;
    private final AtomicLong lastPoll = new AtomicLong();
    /** Bound series: key -> value holder (items as whole numbers, value as major units). */
    private final Map<String, AtomicDouble> items = new ConcurrentHashMap<>();
    private final Map<String, AtomicDouble> values = new ConcurrentHashMap<>();
    private final Set<String> units = new LinkedHashSet<>();

    public GiftBusinessGauge(StripePurchaseRepository purchases, MeterRegistry registry) {
        this(purchases, registry, Clock.systemUTC());
    }

    GiftBusinessGauge(StripePurchaseRepository purchases, MeterRegistry registry, Clock clock) {
        this.purchases = purchases;
        this.registry = registry;
        this.clock = clock;
        this.failures = registry == null ? null : Counter.builder(POLL_FAILURES)
                .description("Business-metrics polls that failed; the last values are kept.")
                .tag("source", SOURCE)
                .register(registry);
        if (registry != null) {
            Gauge.builder(LAST_POLL, lastPoll, AtomicLong::get)
                    .description("Unix time of the last successful business-metrics poll.")
                    .tag("source", SOURCE)
                    .register(registry);
        }
        units.add(DEFAULT_UNIT);
        bindSeries(DEFAULT_UNIT);
    }

    /** The label value for a currency, capped at {@link #MAX_UNITS} distinct units. */
    private String unitLabel(String currency) {
        String u = currency == null ? OTHER : currency.toUpperCase(java.util.Locale.ROOT);
        if (units.contains(u)) return u;
        if (units.size() >= MAX_UNITS) {
            bindSeries(OTHER);
            return OTHER;
        }
        units.add(u);
        bindSeries(u);
        return u;
    }

    /** Binds every state, kind and delivery for one unit at zero. Idempotent. */
    private void bindSeries(String unit) {
        for (String state : STATES) for (String kind : KINDS) for (String delivery : DELIVERIES) {
            String k = key(state, unit, kind, delivery);
            if (items.containsKey(k)) continue;
            AtomicDouble n = new AtomicDouble();
            AtomicDouble v = new AtomicDouble();
            items.put(k, n);
            values.put(k, v);
            if (registry != null) {
                Gauge.builder(ITEMS, n, AtomicDouble::get)
                        .description("Items (one gift each) by state. Lifetime issued is the sum over states.")
                        .tags("product", "gift", "state", state, "unit", unit, "kind", kind, "delivery", delivery)
                        .register(registry);
                Gauge.builder(VALUE, v, AtomicDouble::get)
                        .description("Face value in major units of unit, by state.")
                        .tags("product", "gift", "state", state, "unit", unit, "kind", kind, "delivery", delivery)
                        .register(registry);
            }
        }
    }

    private static String key(String state, String unit, String kind, String delivery) {
        return state + "|" + unit + "|" + kind + "|" + delivery;
    }

    /** ISO 4217 minor digits; 2 when the code is unknown. */
    static int decimals(String currency) {
        try {
            int d = Currency.getInstance(currency.toUpperCase(java.util.Locale.ROOT)).getDefaultFractionDigits();
            return d < 0 ? 2 : d;
        } catch (RuntimeException e) {
            return 2;
        }
    }

    /** The contract state an item is counted in. Package-private for the mapping test. */
    static String itemState(StripePurchase.Status status, GiftOutcome outcome) {
        if (status == StripePurchase.Status.REFUNDED) return "returned";
        // An outcome report proves the gift reached someone, so it applies to a row whose discharge
        // is still unconfirmed (ISSUED, NEEDS_ATTENTION) too. Only a refund overrides it.
        if (outcome == null) return "outstanding";
        return switch (outcome) {
            case PARTLY_USED -> "outstanding";
            case CLAIMED -> "redeemed";
            case EXPIRED -> "expired";
            case RETURNED -> "returned";
        };
    }

    @Scheduled(fixedDelayString = "${business-metrics.poll-interval:PT60S}")
    public void poll() {
        List<Object[]> rows;
        try {
            rows = purchases.summariseGifts(DELIVERIES);
        } catch (RuntimeException e) {
            log.warn("gift_business_metrics_poll_failed", e);
            if (failures != null) failures.increment();
            return;
        }
        Map<String, double[]> next = new HashMap<>();
        for (Object[] r : rows) {
            String delivery = (String) r[0];
            String kind = PurchaseDraft.CODE.equals(r[1]) ? "code" : "voucher";
            StripePurchase.Status status = (StripePurchase.Status) r[2];
            GiftOutcome outcome = (GiftOutcome) r[3];
            String currency = (String) r[4];
            long count = ((Number) r[5]).longValue();
            long amount = r[6] == null ? 0 : ((Number) r[6]).longValue();
            long remaining = r[7] == null ? amount : Math.min(amount, ((Number) r[7]).longValue());
            String unit = unitLabel(currency);
            double scale = Math.pow(10, decimals(currency == null ? "" : currency));
            String state = itemState(status, outcome);
            add(next, key(state, unit, kind, delivery), count, 0);
            if (status != StripePurchase.Status.REFUNDED && outcome == GiftOutcome.CLAIMED) {
                add(next, key("redeemed", unit, kind, delivery), 0, amount / scale);
            } else if (status == StripePurchase.Status.REFUNDED) {
                add(next, key("returned", unit, kind, delivery), 0, amount / scale);
            } else {
                // The unspent part stays with the item's state; whatever was spent is redeemed.
                add(next, key(state, unit, kind, delivery), 0, remaining / scale);
                if (amount > remaining) add(next, key("redeemed", unit, kind, delivery), 0, (amount - remaining) / scale);
            }
        }
        for (Map.Entry<String, AtomicDouble> e : items.entrySet()) {
            double[] nv = next.get(e.getKey());
            e.getValue().set(nv == null ? 0 : nv[0]);
            values.get(e.getKey()).set(nv == null ? 0 : nv[1]);
        }
        lastPoll.set(clock.instant().getEpochSecond());
    }

    private static void add(Map<String, double[]> m, String k, long n, double v) {
        double[] a = m.computeIfAbsent(k, x -> new double[2]);
        a[0] += n;
        a[1] += v;
    }

    double items(String state, String unit, String kind, String delivery) {
        AtomicDouble d = items.get(key(state, unit, kind, delivery));
        return d == null ? Double.NaN : d.get();
    }

    double value(String state, String unit, String kind, String delivery) {
        AtomicDouble d = values.get(key(state, unit, kind, delivery));
        return d == null ? Double.NaN : d.get();
    }

    long lastPollSeconds() {
        return lastPoll.get();
    }

    Collection<String> boundUnits() {
        return units;
    }

    /** A double a gauge can read while the poller writes it. */
    static final class AtomicDouble {
        private volatile double v;

        double get() {
            return v;
        }

        void set(double next) {
            v = next;
        }
    }
}
