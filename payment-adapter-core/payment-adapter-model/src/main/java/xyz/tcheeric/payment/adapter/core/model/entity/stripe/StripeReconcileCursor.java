package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How far the Stripe reconciler has read one connected account's Checkout Sessions
 * (payment-adapter#272). See {@code V18__stripe_reconcile_cursor.sql} for each column's meaning.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "stripe_reconcile_cursor")
public class StripeReconcileCursor {

    @Id
    @Column(name = "account_id", nullable = false)
    private String accountId;

    /** Every session created before this instant (less the overlap) has been reconciled. */
    @Column(name = "created_through", nullable = false)
    private Instant createdThrough;

    /** Upper bound of the pass in progress; null between passes. */
    @Column(name = "pass_until")
    private Instant passUntil;

    /** Last session handled in the pass in progress; null at the start of a pass. */
    @Column(name = "resume_after")
    private String resumeAfter;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "leased_by", length = 128)
    private String leasedBy;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
