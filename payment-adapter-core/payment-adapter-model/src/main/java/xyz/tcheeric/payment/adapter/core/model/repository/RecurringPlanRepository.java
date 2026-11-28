package xyz.tcheeric.payment.adapter.core.model.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;

public interface RecurringPlanRepository extends CrudRepository<RecurringPlan, UUID> {

    Optional<RecurringPlan> findBySubscriptionId(String subscriptionId);

    /** True while any plan on the account may still be charging a buyer. Blocks disconnecting (spec R8). */
    boolean existsByConnectedAccountIdAndStatusIn(String connectedAccountId, Collection<RecurringPlan.Status> statuses);

    java.util.List<RecurringPlan> findByConnectedAccountIdAndStatusIn(
            String connectedAccountId, Collection<RecurringPlan.Status> statuses);

    /**
     * Plans worth reading from Stripe now, least recently checked first. A plan qualifies when it is running
     * ({@code live}), or ended within the lookback ({@code ended}: a late instalment can still land), is older than the
     * webhook grace ({@code before}), and was not checked within the recheck window ({@code recheckBefore}).
     */
    @Query("select p from RecurringPlan p where (p.status in :live or (p.status in :ended and p.updatedAt > :since))"
            + " and p.createdAt < :before and (p.reconciledAt is null or p.reconciledAt < :recheckBefore)"
            + " order by p.reconciledAt asc nulls first, p.createdAt asc")
    List<RecurringPlan> findReconcilable(@Param("live") Collection<RecurringPlan.Status> live,
            @Param("ended") Collection<RecurringPlan.Status> ended, @Param("since") Instant since,
            @Param("before") Instant before, @Param("recheckBefore") Instant recheckBefore, Pageable page);

    /**
     * Claims a plan for this run in ONE statement, only if it is still unclaimed in the window. Returns 1 for the
     * winner and 0 for a replica that lost the race, which then skips the plan. Written with a bare timestamp rather
     * than by saving the entity, so a status the webhook changed meanwhile is not overwritten.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecurringPlan p set p.reconciledAt = :now where p.id = :id"
            + " and (p.reconciledAt is null or p.reconciledAt < :recheckBefore)")
    int claim(@Param("id") UUID id, @Param("now") Instant now, @Param("recheckBefore") Instant recheckBefore);
}
