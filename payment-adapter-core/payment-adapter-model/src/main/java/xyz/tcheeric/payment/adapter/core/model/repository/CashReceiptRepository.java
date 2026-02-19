package xyz.tcheeric.payment.adapter.core.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for CashReceipt entities.
 */
public interface CashReceiptRepository extends CrudRepository<CashReceipt, Long> {

    @Query("select r from cash_receipt r where r.ref = :ref")
    Optional<CashReceipt> findByRef(@Param("ref") String ref);

    @Query("select r from cash_receipt r where r.eventId = :eventId")
    Optional<CashReceipt> findByEventId(@Param("eventId") String eventId);

    @Modifying
    @Query("delete from cash_receipt r where r.confirmedAt < :before")
    void deleteByConfirmedAtBefore(@Param("before") Instant before);
}
