package xyz.tcheeric.payment.adapter.cash.webhook;

import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.payment.adapter.core.model.entity.CashIntent;
import xyz.tcheeric.payment.adapter.core.model.repository.CashIntentRepository;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookParseException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookSignatureException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import java.io.StringReader;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CashWebhookHandlerTest {

    private CashWebhookHandler handler;

    @Mock
    private HttpServletRequest request;

    @Mock
    private CashIntentRepository intentRepository;

    @BeforeEach
    void setUp() {
        handler = new CashWebhookHandler(intentRepository);
    }

    // Verifies the payment type identifier
    @Test
    void testGetPaymentType() {
        assertEquals("cash", handler.getPaymentType());
    }

    // Verifies parsing a valid kind 5201 event
    @Test
    void testParseValidPayload() throws Exception {
        String json = buildIntentEventJson("event123", "aabb11", "03custpubkey", Instant.now().getEpochSecond());
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        CashWebhookPayload payload = handler.parsePayload(request);

        assertEquals("event123", payload.getEventId());
        assertEquals(5201, payload.getKind());
        assertEquals("aabb11", payload.getRef());
        assertEquals("03custpubkey", payload.getCustomerPubkey());
    }

    // Verifies parsing fails on empty body
    @Test
    void testParseEmptyBody() throws Exception {
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader("")));

        assertThrows(WebhookParseException.class, () -> handler.parsePayload(request));
    }

    // Verifies parsing fails on wrong event kind
    @Test
    void testParseWrongKind() throws Exception {
        String json = """
            {"id":"event123","kind":5200,"pubkey":"03pub","tags":[["ref","aabb11"]],"content":"","sig":"abc123"}
            """;
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        assertThrows(WebhookParseException.class, () -> handler.parsePayload(request));
    }

    // Verifies parsing fails when ref tag is missing
    @Test
    void testParseMissingRef() throws Exception {
        String json = """
            {"id":"event123","kind":5201,"pubkey":"03pub","tags":[],"content":"","sig":"abc123"}
            """;
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));

        assertThrows(WebhookParseException.class, () -> handler.parsePayload(request));
    }

    // Verifies signature validation rejects missing signature
    @Test
    void testValidateSignatureRejectsMissing() {
        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId("e1")
                .kind(5201)
                .ref("aabb11")
                .customerPubkey("03pub")
                .signature(null)
                .build();

        assertThrows(WebhookSignatureException.class,
                () -> handler.validateSignature(payload, request));
    }

    // Verifies handling a valid intent creates a CashIntent
    @Test
    void testHandleValidIntent() throws Exception {
        when(intentRepository.existsByEventId("event001")).thenReturn(false);
        when(intentRepository.save(any(CashIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId("event001")
                .kind(5201)
                .ref("aabb11")
                .customerPubkey("03cust")
                .customerTimestamp(Instant.now().getEpochSecond())
                .signature("sig")
                .rawEvent("{}")
                .build();

        WebhookResult result = handler.handle(payload);

        assertNotNull(result);
        verify(intentRepository).save(any(CashIntent.class));
    }

    // Verifies duplicate events are rejected
    @Test
    void testHandleDuplicateThrows() {
        when(intentRepository.existsByEventId("event002")).thenReturn(true);

        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId("event002")
                .kind(5201)
                .ref("aabb11")
                .customerPubkey("03cust")
                .customerTimestamp(Instant.now().getEpochSecond())
                .signature("sig")
                .build();

        assertThrows(WebhookDuplicateException.class, () -> handler.handle(payload));
    }

    // Verifies invalid ref format is rejected
    @Test
    void testHandleInvalidRef() {
        when(intentRepository.existsByEventId("event003")).thenReturn(false);

        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId("event003")
                .kind(5201)
                .ref("ab")
                .customerPubkey("03cust")
                .signature("sig")
                .build();

        assertThrows(WebhookProcessingException.class, () -> handler.handle(payload));
    }

    // Verifies non-hex ref is rejected
    @Test
    void testHandleNonHexRef() {
        when(intentRepository.existsByEventId("event004")).thenReturn(false);

        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId("event004")
                .kind(5201)
                .ref("not-hex!")
                .customerPubkey("03cust")
                .signature("sig")
                .build();

        assertThrows(WebhookProcessingException.class, () -> handler.handle(payload));
    }

    // Verifies future timestamp is rejected
    @Test
    void testHandleFutureTimestampRejected() {
        when(intentRepository.existsByEventId("event005")).thenReturn(false);

        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId("event005")
                .kind(5201)
                .ref("aabb11")
                .customerPubkey("03cust")
                .customerTimestamp(Instant.now().getEpochSecond() + 600)
                .signature("sig")
                .build();

        assertThrows(WebhookProcessingException.class, () -> handler.handle(payload));
    }

    // Verifies intent callback is invoked on successful handle
    @Test
    void testIntentReceivedCallback() throws Exception {
        when(intentRepository.existsByEventId("event020")).thenReturn(false);
        when(intentRepository.save(any(CashIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicReference<String> callbackRef = new AtomicReference<>();
        handler.setIntentReceivedCallback((ref, intent) -> callbackRef.set(ref));

        CashWebhookPayload payload = CashWebhookPayload.builder()
                .eventId("event020")
                .kind(5201)
                .ref("aabb11")
                .customerPubkey("03cust")
                .customerTimestamp(Instant.now().getEpochSecond())
                .signature("sig")
                .build();

        handler.handle(payload);

        assertEquals("aabb11", callbackRef.get());
    }

    private String buildIntentEventJson(String eventId, String ref, String pubkey, long timestamp) {
        return String.format("""
            {
              "id": "%s",
              "kind": 5201,
              "pubkey": "%s",
              "created_at": %d,
              "tags": [["ref", "%s"]],
              "content": "",
              "sig": "304402signature"
            }
            """, eventId, pubkey, timestamp, ref);
    }
}
