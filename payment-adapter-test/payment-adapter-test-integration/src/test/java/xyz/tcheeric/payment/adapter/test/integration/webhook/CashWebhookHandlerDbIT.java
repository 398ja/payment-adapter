package xyz.tcheeric.payment.adapter.test.integration.webhook;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import xyz.tcheeric.payment.adapter.cash.webhook.CashWebhookHandler;
import xyz.tcheeric.payment.adapter.cash.webhook.CashWebhookPayload;
import xyz.tcheeric.payment.adapter.core.model.entity.CashIntent;
import xyz.tcheeric.payment.adapter.core.model.repository.CashIntentRepository;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;
import xyz.tcheeric.payment.adapter.test.integration.TestDataFactory;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Import(CashWebhookHandler.class)
class CashWebhookHandlerDbIT extends BasePostgresIT {

    @Autowired
    private CashWebhookHandler webhookHandler;

    @Autowired
    private CashIntentRepository intentRepository;

    @BeforeEach
    void cleanUp() {
        intentRepository.deleteAll();
    }

    // Verifies that handling a valid intent payload persists the intent to the database
    @Test
    void handle_validPayload_persistsIntent() {
        String ref = TestDataFactory.uniqueRef();
        String eventId = TestDataFactory.uniqueEventId();
        String pubkey = "02" + "a".repeat(64);

        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId(eventId)
                .kind(5201)
                .ref(ref)
                .customerPubkey(pubkey)
                .proof("1234")
                .customerTimestamp(Instant.now().getEpochSecond())
                .build();

        WebhookResult result = webhookHandler.handle(payload);

        assertThat(result.processed()).isTrue();
        assertThat(intentRepository.existsByEventId(eventId)).isTrue();

        CashIntent saved = intentRepository.findByEventId(eventId).orElseThrow();
        assertThat(saved.getRef()).isEqualTo(ref);
        assertThat(saved.getCustomerPubkey()).isEqualTo(pubkey);
    }

    // Verifies that duplicate event IDs are detected and rejected
    @Test
    void handle_duplicateEventId_returnsDuplicate() {
        String ref = TestDataFactory.uniqueRef();
        String eventId = TestDataFactory.uniqueEventId();

        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId(eventId)
                .kind(5201)
                .ref(ref)
                .customerPubkey("02" + "a".repeat(64))
                .customerTimestamp(Instant.now().getEpochSecond())
                .build();

        webhookHandler.handle(payload);

        org.junit.jupiter.api.Assertions.assertThrows(WebhookDuplicateException.class,
                () -> webhookHandler.handle(payload));
    }

    // Verifies that the intent received callback is invoked with correct data
    @Test
    void handle_withCallback_callbackInvokedWithCorrectData() {
        AtomicReference<String> callbackRef = new AtomicReference<>();
        AtomicReference<CashIntent> callbackIntent = new AtomicReference<>();

        webhookHandler.setIntentReceivedCallback((r, i) -> {
            callbackRef.set(r);
            callbackIntent.set(i);
        });

        String ref = TestDataFactory.uniqueRef();
        String eventId = TestDataFactory.uniqueEventId();

        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId(eventId)
                .kind(5201)
                .ref(ref)
                .customerPubkey("02" + "b".repeat(64))
                .proof("5678")
                .customerTimestamp(Instant.now().getEpochSecond())
                .build();

        webhookHandler.handle(payload);

        assertThat(callbackRef.get()).isEqualTo(ref);
        assertThat(callbackIntent.get()).isNotNull();
        assertThat(callbackIntent.get().getProof()).isEqualTo("5678");
    }

    // Verifies that multiple intents for the same ref are all persisted
    @Test
    void handle_multipleIntentsSameRef_allPersisted() {
        String ref = TestDataFactory.uniqueRef();

        for (int i = 0; i < 3; i++) {
            CashWebhookPayload payload = CashWebhookPayload.builder()
                    .eventId(TestDataFactory.uniqueEventId())
                    .kind(5201)
                    .ref(ref)
                    .customerPubkey("02" + String.valueOf((char) ('a' + i)).repeat(64))
                    .customerTimestamp(Instant.now().getEpochSecond())
                    .build();
            webhookHandler.handle(payload);
        }

        List<CashIntent> intents = intentRepository.findByRef(ref);
        assertThat(intents).hasSize(3);
    }

    // Verifies that future timestamps beyond tolerance are rejected
    @Test
    void handle_farFutureTimestamp_throwsProcessingException() {
        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId(TestDataFactory.uniqueEventId())
                .kind(5201)
                .ref(TestDataFactory.uniqueRef())
                .customerPubkey("02" + "d".repeat(64))
                .customerTimestamp(Instant.now().plusSeconds(600).getEpochSecond())
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(WebhookProcessingException.class,
                () -> webhookHandler.handle(payload));
    }

    // Verifies that invalid ref format is rejected
    @Test
    void handle_invalidRefFormat_throwsProcessingException() {
        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId(TestDataFactory.uniqueEventId())
                .kind(5201)
                .ref("!!invalid!!")
                .customerPubkey("02" + "e".repeat(64))
                .customerTimestamp(Instant.now().getEpochSecond())
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(WebhookProcessingException.class,
                () -> webhookHandler.handle(payload));
    }

    // Verifies that the handler correctly identifies its payment type as "cash"
    @Test
    void getPaymentType_returnsCash() {
        assertThat(webhookHandler.getPaymentType()).isEqualTo("cash");
    }
}
