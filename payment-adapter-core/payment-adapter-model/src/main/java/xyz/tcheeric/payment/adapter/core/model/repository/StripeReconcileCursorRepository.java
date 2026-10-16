package xyz.tcheeric.payment.adapter.core.model.repository;

import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeReconcileCursor;

/** The Stripe reconciler's per-account cursors (payment-adapter#272). */
public interface StripeReconcileCursorRepository extends CrudRepository<StripeReconcileCursor, String> {

    /**
     * Lease one account's cursor until {@code until}: ONE conditional UPDATE, so of two replicas
     * racing the same account exactly one gets 1. Mirrors the purchase claim; PA has no ShedLock.
     *
     * @return rows updated, 0 or 1
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StripeReconcileCursor c set c.leaseUntil = :until, c.leasedBy = :worker"
            + " where c.accountId = :account and (c.leaseUntil is null or c.leaseUntil < :now)")
    int lease(@Param("account") String account, @Param("worker") String worker,
            @Param("now") Instant now, @Param("until") Instant until);
}
