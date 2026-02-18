package xyz.tcheeric.payment.adapter.test.integration.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;
import xyz.tcheeric.payment.adapter.test.integration.TestDataFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class CashInvoiceRepositoryIT extends BasePostgresIT {

    @Autowired
    private CashInvoiceRepository invoiceRepository;

    @BeforeEach
    void cleanUp() {
        invoiceRepository.deleteAll();
    }

    // Verifies that a saved invoice can be retrieved by its unique ref
    @Test
    void findByRef_existingInvoice_returnsInvoice() {
        CashInvoice invoice = TestDataFactory.createInvoice();
        invoiceRepository.save(invoice);

        Optional<CashInvoice> found = invoiceRepository.findByRef(invoice.getRef());

        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualTo(1000);
        assertThat(found.get().getFiat()).isEqualTo("USD");
    }

    // Verifies that findByRef returns empty for non-existent ref
    @Test
    void findByRef_nonExistentRef_returnsEmpty() {
        Optional<CashInvoice> found = invoiceRepository.findByRef("nonexistent");
        assertThat(found).isEmpty();
    }

    // Verifies that existsByRef returns true for existing ref
    @Test
    void existsByRef_existingRef_returnsTrue() {
        CashInvoice invoice = TestDataFactory.createInvoice();
        invoiceRepository.save(invoice);

        assertThat(invoiceRepository.existsByRef(invoice.getRef())).isTrue();
    }

    // Verifies that existsByRef returns false for non-existent ref
    @Test
    void existsByRef_nonExistentRef_returnsFalse() {
        assertThat(invoiceRepository.existsByRef("missing")).isFalse();
    }

    // Verifies that invoices can be filtered by status
    @Test
    void findByStatus_returnsMatchingInvoices() {
        CashInvoice pending = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PENDING);
        CashInvoice paid = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PAID);
        invoiceRepository.save(pending);
        invoiceRepository.save(paid);

        List<CashInvoice> found = invoiceRepository.findByStatus(CashInvoiceStatus.PENDING);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRef()).isEqualTo(pending.getRef());
    }

    // Verifies that findByStatusIn returns invoices matching any given status
    @Test
    void findByStatusIn_returnsAllMatchingStatuses() {
        CashInvoice created = TestDataFactory.createInvoice();
        CashInvoice pending = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PENDING);
        CashInvoice paid = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PAID);
        invoiceRepository.save(created);
        invoiceRepository.save(pending);
        invoiceRepository.save(paid);

        List<CashInvoice> found = invoiceRepository.findByStatusIn(
                List.of(CashInvoiceStatus.CREATED, CashInvoiceStatus.PENDING));

        assertThat(found).hasSize(2);
    }

    // Verifies that findExpiredInvoices returns invoices past expiry in given statuses
    @Test
    void findExpiredInvoices_returnsExpiredPendingInvoices() {
        CashInvoice expired = TestDataFactory.createExpiredInvoice();
        CashInvoice active = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PENDING);
        invoiceRepository.save(expired);
        invoiceRepository.save(active);

        List<CashInvoice> found = invoiceRepository.findExpiredInvoices(
                List.of(CashInvoiceStatus.PENDING, CashInvoiceStatus.INTENT_RECEIVED),
                Instant.now());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRef()).isEqualTo(expired.getRef());
    }

    // Verifies that findExpiredInvoices excludes terminal-status invoices
    @Test
    void findExpiredInvoices_excludesTerminalStatuses() {
        CashInvoice paid = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PAID);
        paid.setExpiresAt(Instant.now().minusSeconds(60));
        invoiceRepository.save(paid);

        List<CashInvoice> found = invoiceRepository.findExpiredInvoices(
                List.of(CashInvoiceStatus.PENDING), Instant.now());

        assertThat(found).isEmpty();
    }

    // Verifies that findByEventId returns the invoice matching the event ID
    @Test
    void findByEventId_existingEventId_returnsInvoice() {
        CashInvoice invoice = TestDataFactory.createInvoice();
        String eventId = TestDataFactory.uniqueEventId();
        invoice.setEventId(eventId);
        invoiceRepository.save(invoice);

        Optional<CashInvoice> found = invoiceRepository.findByEventId(eventId);

        assertThat(found).isPresent();
        assertThat(found.get().getRef()).isEqualTo(invoice.getRef());
    }

    // Verifies that duplicate ref values are rejected by the unique constraint
    @Test
    void save_duplicateRef_throwsException() {
        CashInvoice first = TestDataFactory.createInvoice();
        invoiceRepository.save(first);

        CashInvoice duplicate = TestDataFactory.createInvoice(2000, "EUR");
        duplicate.setRef(first.getRef());

        assertThatThrownBy(() -> {
            invoiceRepository.save(duplicate);
            invoiceRepository.findAll(); // flush
        }).isInstanceOf(Exception.class);
    }

    // Verifies that all entity fields are persisted and read correctly
    @Test
    void save_allFields_persistedCorrectly() {
        CashInvoice invoice = TestDataFactory.createInvoice(500, "EUR");
        invoice.setMemo("Coffee purchase");
        invoice.setProofCode("1234");
        invoice.setEventId(TestDataFactory.uniqueEventId());
        invoice.setStatus(CashInvoiceStatus.PENDING);
        invoice.setPublishedAt(Instant.now());
        invoiceRepository.save(invoice);

        CashInvoice loaded = invoiceRepository.findByRef(invoice.getRef()).orElseThrow();

        assertThat(loaded.getAmount()).isEqualTo(500);
        assertThat(loaded.getFiat()).isEqualTo("EUR");
        assertThat(loaded.getMemo()).isEqualTo("Coffee purchase");
        assertThat(loaded.getProofCode()).isEqualTo("1234");
        assertThat(loaded.getStatus()).isEqualTo(CashInvoiceStatus.PENDING);
        assertThat(loaded.getPublishedAt()).isNotNull();
        assertThat(loaded.getRelayUrls()).isNotNull();
    }

    // Verifies that deleteByCreatedAtBefore removes old invoices
    @Test
    void deleteByCreatedAtBefore_removesOldInvoices() {
        CashInvoice old = TestDataFactory.createInvoice();
        // manually backdate the createdAt
        old.setCreatedAt(Instant.now().minusSeconds(86400 * 60));
        invoiceRepository.save(old);

        CashInvoice recent = TestDataFactory.createInvoice();
        invoiceRepository.save(recent);

        invoiceRepository.deleteByCreatedAtBefore(Instant.now().minusSeconds(86400));

        assertThat(invoiceRepository.findByRef(old.getRef())).isEmpty();
        assertThat(invoiceRepository.findByRef(recent.getRef())).isPresent();
    }

    // Verifies that status updates are persisted correctly
    @Test
    void save_statusUpdate_persistsNewStatus() {
        CashInvoice invoice = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PENDING);
        invoiceRepository.save(invoice);

        invoice.setStatus(CashInvoiceStatus.INTENT_RECEIVED);
        invoice.setIntentReceivedAt(Instant.now());
        invoiceRepository.save(invoice);

        CashInvoice loaded = invoiceRepository.findByRef(invoice.getRef()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CashInvoiceStatus.INTENT_RECEIVED);
        assertThat(loaded.getIntentReceivedAt()).isNotNull();
    }
}
