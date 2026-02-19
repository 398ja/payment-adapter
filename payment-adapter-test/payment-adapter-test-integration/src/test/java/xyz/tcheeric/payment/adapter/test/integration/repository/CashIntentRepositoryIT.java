package xyz.tcheeric.payment.adapter.test.integration.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.model.entity.CashIntent;
import xyz.tcheeric.payment.adapter.core.model.repository.CashIntentRepository;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;
import xyz.tcheeric.payment.adapter.test.integration.TestDataFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class CashIntentRepositoryIT extends BasePostgresIT {

    @Autowired
    private CashIntentRepository intentRepository;

    @BeforeEach
    void cleanUp() {
        intentRepository.deleteAll();
    }

    // Verifies that a saved intent can be found by its ref
    @Test
    void findByRef_existingRef_returnsIntents() {
        String ref = TestDataFactory.uniqueRef();
        CashIntent intent = TestDataFactory.createIntent(ref);
        intentRepository.save(intent);

        List<CashIntent> found = intentRepository.findByRef(ref);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCustomerPubkey()).isNotNull();
    }

    // Verifies that findByRef returns empty list for non-existent ref
    @Test
    void findByRef_nonExistentRef_returnsEmpty() {
        List<CashIntent> found = intentRepository.findByRef("missing");
        assertThat(found).isEmpty();
    }

    // Verifies that findByEventId returns the intent matching the event ID
    @Test
    void findByEventId_existingEventId_returnsIntent() {
        CashIntent intent = TestDataFactory.createIntent(TestDataFactory.uniqueRef());
        intentRepository.save(intent);

        Optional<CashIntent> found = intentRepository.findByEventId(intent.getEventId());

        assertThat(found).isPresent();
        assertThat(found.get().getRef()).isEqualTo(intent.getRef());
    }

    // Verifies that existsByEventId returns true for existing event
    @Test
    void existsByEventId_existing_returnsTrue() {
        CashIntent intent = TestDataFactory.createIntent(TestDataFactory.uniqueRef());
        intentRepository.save(intent);

        assertThat(intentRepository.existsByEventId(intent.getEventId())).isTrue();
    }

    // Verifies that existsByEventId returns false for non-existent event
    @Test
    void existsByEventId_nonExistent_returnsFalse() {
        assertThat(intentRepository.existsByEventId("nonexistent")).isFalse();
    }

    // Verifies that duplicate event IDs are rejected by unique constraint
    @Test
    void save_duplicateEventId_throwsException() {
        CashIntent first = TestDataFactory.createIntent(TestDataFactory.uniqueRef());
        intentRepository.save(first);

        CashIntent duplicate = TestDataFactory.createIntent(TestDataFactory.uniqueRef());
        duplicate.setEventId(first.getEventId());

        assertThatThrownBy(() -> {
            intentRepository.save(duplicate);
            intentRepository.findAll(); // flush
        }).isInstanceOf(Exception.class);
    }

    // Verifies that deleteByReceivedAtBefore removes old intents
    @Test
    void deleteByReceivedAtBefore_removesOldIntents() {
        CashIntent old = TestDataFactory.createIntent(TestDataFactory.uniqueRef());
        old.setReceivedAt(Instant.now().minusSeconds(86400 * 60));
        intentRepository.save(old);

        CashIntent recent = TestDataFactory.createIntent(TestDataFactory.uniqueRef());
        intentRepository.save(recent);

        intentRepository.deleteByReceivedAtBefore(Instant.now().minusSeconds(86400));

        assertThat(intentRepository.findByEventId(old.getEventId())).isEmpty();
        assertThat(intentRepository.findByEventId(recent.getEventId())).isPresent();
    }
}
