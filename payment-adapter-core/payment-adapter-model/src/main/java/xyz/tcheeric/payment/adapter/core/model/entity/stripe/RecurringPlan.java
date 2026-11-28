package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An instalment plan: a Stripe subscription on the stall's own connected account, and who it pays vouchers to.
 * See {@code V25__stripe_recurring_plans.sql}.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "recurring_plan", indexes = {
        @Index(name = "idx_recurring_plan_account_status", columnList = "connected_account_id, status"),
        @Index(name = "idx_recurring_plan_buyer", columnList = "buyer_pubkey"),
        @Index(name = "idx_recurring_plan_reconciled", columnList = "reconciled_at")
})
public class RecurringPlan {

    public enum Status {
        /** Created, the buyer has not finished Stripe's page yet. */
        PENDING,
        /** The subscription exists and at least the first instalment was paid. */
        ACTIVE,
        /** The last instalment failed. Stripe retries; nothing is issued meanwhile. */
        PAST_DUE,
        /** Every instalment of a fixed-term plan was paid. */
        COMPLETED,
        /** The buyer or the stall ended it before completion. */
        CANCELLED,
        /** The stall disconnected from Imani. Stripe keeps charging, Imani no longer sees it. */
        ORPHANED,
        /** A refund or dispute touched an instalment. An operator looks; nothing is cancelled automatically. */
        ATTENTION
    }

    /** Statuses in which a plan is not finished: a buyer may be about to pay, or already is. */
    public static final Set<Status> LIVE = EnumSet.of(Status.PENDING, Status.ACTIVE, Status.PAST_DUE, Status.ATTENTION);

    /**
     * Statuses in which a subscription EXISTS and may be charging a buyer. PENDING is deliberately not here: it has
     * no subscription, and a public endpoint creates PENDING rows, so counting them would let anyone stop a stall
     * from ever disconnecting.
     */
    public static final Set<Status> CHARGING = EnumSet.of(Status.ACTIVE, Status.PAST_DUE, Status.ATTENTION);

    @Id
    private UUID id;

    @Column(name = "connected_account_id", nullable = false)
    private String connectedAccountId;

    @Column(name = "stall_pubkey", nullable = false, length = 64)
    private String stallPubkey;

    @Column(name = "buyer_pubkey", nullable = false, length = 64)
    private String buyerPubkey;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "interval_unit", nullable = false, length = 8)
    private String intervalUnit = "month";

    /** Null = open-ended. */
    @Column(name = "total_instalments")
    private Integer totalInstalments;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "checkout_session_id")
    private String checkoutSessionId;

    @Column(name = "subscription_id", unique = true)
    private String subscriptionId;

    @Column(name = "livemode", nullable = false)
    private boolean livemode;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    /** When the instalment reconciler last claimed this plan; null until it first does. */
    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isLive() {
        return LIVE.contains(status);
    }
}
