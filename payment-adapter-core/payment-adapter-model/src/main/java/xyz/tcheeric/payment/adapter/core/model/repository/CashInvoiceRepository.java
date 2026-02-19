package xyz.tcheeric.payment.adapter.core.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for CashInvoice entities.
 */
public interface CashInvoiceRepository extends PagingAndSortingRepository<CashInvoice, Long>,
        CrudRepository<CashInvoice, Long> {

    @Query("select i from cash_invoice i where i.ref = :ref")
    Optional<CashInvoice> findByRef(@Param("ref") String ref);

    @Query("select i from cash_invoice i where i.status = :status")
    List<CashInvoice> findByStatus(@Param("status") CashInvoiceStatus status);

    @Query("select i from cash_invoice i where i.eventId = :eventId")
    Optional<CashInvoice> findByEventId(@Param("eventId") String eventId);

    boolean existsByRef(String ref);

    @Query("select i from cash_invoice i where i.status in :statuses")
    List<CashInvoice> findByStatusIn(@Param("statuses") List<CashInvoiceStatus> statuses);

    @Query("select i from cash_invoice i where i.status in :statuses and i.expiresAt < :now")
    List<CashInvoice> findExpiredInvoices(
            @Param("statuses") List<CashInvoiceStatus> statuses,
            @Param("now") Instant now);

    @Modifying
    @Query("delete from cash_invoice i where i.createdAt < :before")
    void deleteByCreatedAtBefore(@Param("before") Instant before);
}
