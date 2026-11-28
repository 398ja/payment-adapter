package xyz.tcheeric.payment.adapter.core.model.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;

public interface RecurringPlanRepository extends CrudRepository<RecurringPlan, UUID> {

    Optional<RecurringPlan> findBySubscriptionId(String subscriptionId);

    /** True while any plan on the account may still be charging a buyer. Blocks disconnecting (spec R8). */
    boolean existsByConnectedAccountIdAndStatusIn(String connectedAccountId, Collection<RecurringPlan.Status> statuses);

    java.util.List<RecurringPlan> findByConnectedAccountIdAndStatusIn(
            String connectedAccountId, Collection<RecurringPlan.Status> statuses);
}
