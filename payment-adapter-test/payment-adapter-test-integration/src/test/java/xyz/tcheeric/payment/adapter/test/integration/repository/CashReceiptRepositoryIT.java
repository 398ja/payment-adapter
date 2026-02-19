package xyz.tcheeric.payment.adapter.test.integration.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashReceiptStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.CashReceiptRepository;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;
import xyz.tcheeric.payment.adapter.test.integration.TestDataFactory;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class CashReceiptRepositoryIT extends BasePostgresIT {

    @Autowired
    private CashReceiptRepository receiptRepository;

    @BeforeEach
    void cleanUp() {
        receiptRepository.deleteAll();
    }

    // Verifies that a saved receipt can be found by ref
    @Test
    void findByRef_existingRef_returnsReceipt() {
        String ref = TestDataFactory.uniqueRef();
        CashReceipt receipt = TestDataFactory.createReceipt(ref);
        receiptRepository.save(receipt);

        Optional<CashReceipt> found = receiptRepository.findByRef(ref);

        assertThat(found).isPresent();
        assertThat(found.get().getAmountReceived()).isEqualTo(1000);
        assertThat(found.get().getStatus()).isEqualTo(CashReceiptStatus.CONFIRMED);
    }

    // Verifies that findByRef returns empty for non-existent ref
    @Test
    void findByRef_nonExistentRef_returnsEmpty() {
        assertThat(receiptRepository.findByRef("missing")).isEmpty();
    }

    // Verifies that findByEventId returns the receipt matching the event ID
    @Test
    void findByEventId_existingEventId_returnsReceipt() {
        String ref = TestDataFactory.uniqueRef();
        CashReceipt receipt = TestDataFactory.createReceipt(ref);
        receiptRepository.save(receipt);

        Optional<CashReceipt> found = receiptRepository.findByEventId(receipt.getEventId());

        assertThat(found).isPresent();
        assertThat(found.get().getRef()).isEqualTo(ref);
    }

    // Verifies that duplicate ref values are rejected by unique constraint
    @Test
    void save_duplicateRef_throwsException() {
        String ref = TestDataFactory.uniqueRef();
        CashReceipt first = TestDataFactory.createReceipt(ref);
        receiptRepository.save(first);

        CashReceipt duplicate = TestDataFactory.createReceipt(ref, 2000);
        duplicate.setEventId(TestDataFactory.uniqueEventId());

        assertThatThrownBy(() -> {
            receiptRepository.save(duplicate);
            receiptRepository.findAll(); // flush
        }).isInstanceOf(Exception.class);
    }

    // Verifies that deleteByConfirmedAtBefore removes old receipts
    @Test
    void deleteByConfirmedAtBefore_removesOldReceipts() {
        String oldRef = TestDataFactory.uniqueRef();
        CashReceipt old = TestDataFactory.createReceipt(oldRef);
        old.setConfirmedAt(Instant.now().minusSeconds(86400 * 60));
        receiptRepository.save(old);

        String recentRef = TestDataFactory.uniqueRef();
        CashReceipt recent = TestDataFactory.createReceipt(recentRef);
        receiptRepository.save(recent);

        receiptRepository.deleteByConfirmedAtBefore(Instant.now().minusSeconds(86400));

        assertThat(receiptRepository.findByRef(oldRef)).isEmpty();
        assertThat(receiptRepository.findByRef(recentRef)).isPresent();
    }

    // Verifies that all fields are persisted correctly
    @Test
    void save_allFields_persistedCorrectly() {
        String ref = TestDataFactory.uniqueRef();
        CashReceipt receipt = TestDataFactory.createReceipt(ref, 750);
        receiptRepository.save(receipt);

        CashReceipt loaded = receiptRepository.findByRef(ref).orElseThrow();

        assertThat(loaded.getAmountReceived()).isEqualTo(750);
        assertThat(loaded.getStatus()).isEqualTo(CashReceiptStatus.CONFIRMED);
        assertThat(loaded.getConfirmedAt()).isNotNull();
        assertThat(loaded.getEventId()).isNotNull();
        assertThat(loaded.getPublishedAt()).isNotNull();
    }
}
