package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase.AttentionReason;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

/**
 * Card purchases waiting for a person, by reason (payment-adapter#273, imani-deploy#210).
 *
 * <p>{@code gateway_unrecorded}, {@code delivery_exhausted} and {@code stale_issuing} count
 * NEEDS_ATTENTION rows. {@code refunded_after_issue} and {@code disputed} count rows whose coupon
 * exists and whose latest refund or dispute no operator has acknowledged: informational, since
 * refunds and disputes are not supported (#265), and the row's status never changes for them.
 *
 * <p>Same rules as {@link PaidUnissuedGauge} (#268): every reason bound eagerly at zero so the alert
 * sees a healthy zero rather than no series, re-derived from SQL so it survives a restart, and a
 * failed poll logs and holds the last values instead of throwing or reporting a false zero.
 */
@Slf4j
public class NeedsAttentionGauge {

    /** The name the CardPurchaseNeedsAttention alert reads (imani-deploy#210). */
    static final String NAME = "imani_payments_card_needs_attention";

    private final StripePurchaseRepository purchases;
    private final Map<AttentionReason, AtomicLong> values = new EnumMap<>(AttentionReason.class);

    public NeedsAttentionGauge(StripePurchaseRepository purchases, MeterRegistry registry) {
        this.purchases = purchases;
        for (AttentionReason reason : AttentionReason.values()) {
            AtomicLong value = new AtomicLong();
            values.put(reason, value);
            if (registry != null) {
                Gauge.builder(NAME, value, AtomicLong::get)
                        .description("Card purchases needing a person, by reason. refunded_after_issue and"
                                + " disputed are unacknowledged facts on issued coupons, not a status.")
                        .tag("reason", reason.wire())
                        .register(registry);
            }
        }
    }

    @Scheduled(fixedDelayString = "${purchase.attention.gauge-interval:PT30S}")
    public void poll() {
        try {
            Map<AttentionReason, Long> next = new EnumMap<>(AttentionReason.class);
            for (Object[] row : purchases.countByAttentionReason(StripePurchase.Status.NEEDS_ATTENTION)) {
                if (row[0] instanceof AttentionReason reason && row[1] instanceof Number n) {
                    next.put(reason, n.longValue());
                }
            }
            next.put(AttentionReason.REFUNDED_AFTER_ISSUE, purchases.countUnacknowledgedRefundsAfterIssue());
            next.put(AttentionReason.DISPUTED, purchases.countUnacknowledgedDisputes());
            values.forEach((reason, value) -> value.set(next.getOrDefault(reason, 0L)));
        } catch (RuntimeException e) {
            log.warn("needs_attention_poll_failed", e);
        }
    }
}
