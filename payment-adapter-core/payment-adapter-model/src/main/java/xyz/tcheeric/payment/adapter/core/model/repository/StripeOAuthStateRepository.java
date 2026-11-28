package xyz.tcheeric.payment.adapter.core.model.repository;

import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeOAuthState;

public interface StripeOAuthStateRepository extends CrudRepository<StripeOAuthState, String> {

    /**
     * Marks a state used, in ONE statement, only if it exists, is unused and has not expired. The row count is
     * the answer: two concurrent callbacks with the same state cannot both get 1.
     *
     * @return rows updated, 0 or 1
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StripeOAuthState s set s.consumedAt = :now"
            + " where s.state = :state and s.consumedAt is null and s.expiresAt > :now")
    int consume(@Param("state") String state, @Param("now") Instant now);

    /** Housekeeping: drops rows that expired before {@code cutoff}. Used and expired rows serve no purpose. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from StripeOAuthState s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
