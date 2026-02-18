package xyz.tcheeric.payment.adapter.core.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import xyz.tcheeric.payment.adapter.core.model.entity.CashIntent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for CashIntent entities.
 */
public interface CashIntentRepository extends CrudRepository<CashIntent, Long> {

    @Query("select i from cash_intent i where i.ref = :ref")
    List<CashIntent> findByRef(@Param("ref") String ref);

    @Query("select i from cash_intent i where i.eventId = :eventId")
    Optional<CashIntent> findByEventId(@Param("eventId") String eventId);

    boolean existsByEventId(String eventId);

    @Modifying
    @Query("delete from cash_intent i where i.receivedAt < :before")
    void deleteByReceivedAtBefore(@Param("before") Instant before);
}
