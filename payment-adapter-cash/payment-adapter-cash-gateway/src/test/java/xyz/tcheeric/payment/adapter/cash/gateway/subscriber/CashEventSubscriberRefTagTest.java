package xyz.tcheeric.payment.adapter.cash.gateway.subscriber;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;
import nostr.event.impl.GenericEvent;
import nostr.event.json.EventJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.tcheeric.payment.adapter.cash.gateway.service.CashInvoiceService;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.cash.nostr.client.NostrClient;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.repository.CashIntentRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashReceiptRepository;

/** Verifies the subscriber reads the 'ref' tag from relay events as they arrive on the wire. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashEventSubscriberRefTagTest {

    private static final String CANCEL_EVENT_JSON = """
            {"id":"5c83da77af1dec6d7289834998ad7aafbd9e2191396d75ec3cc27f5a77226f36",
             "pubkey":"2bed79f81439ff794cf5ac5f7bff9121e257f399829e472c7a14d3e86fe76984",
             "created_at":1900000000,"kind":5203,
             "tags":[["p","2bed79f81439ff794cf5ac5f7bff9121e257f399829e472c7a14d3e86fe76984"],["ref","inv-123"]],
             "content":"",
             "sig":"%s"}
            """.formatted("a".repeat(128));

    @Mock private CashInvoiceService invoiceService;
    @Mock private NostrClient nostrClient;
    @Mock private CashInvoiceRepository invoiceRepository;
    @Mock private CashIntentRepository intentRepository;
    @Mock private CashReceiptRepository receiptRepository;

    private CashEventSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new CashEventSubscriber(invoiceService, new CashInvoiceStateMachine(), nostrClient,
                invoiceRepository, intentRepository, receiptRepository, null);
        ReflectionTestUtils.setField(subscriber, "enabled", true);
        when(nostrClient.isRunning()).thenReturn(true);
        when(nostrClient.subscribe(anyInt(), anyString(), anyList(), any())).thenReturn("sub-1");
        when(invoiceService.findActiveInvoices()).thenReturn(List.of(anInvoice()));
    }

    // A cancel event parsed from relay JSON cancels the invoice named by its 'ref' tag.
    @Test
    void cancelEventFromRelayJsonCancelsInvoiceNamedByRefTag() throws Exception {
        Consumer<GenericEvent> relayCallback = startAndCaptureRelayCallback();

        relayCallback.accept(EventJsonMapper.getMapper().readValue(CANCEL_EVENT_JSON, GenericEvent.class));

        verify(invoiceService).cancelInvoice("inv-123", "cash.cancelled_by_customer");
    }

    @SuppressWarnings("unchecked")
    private Consumer<GenericEvent> startAndCaptureRelayCallback() {
        subscriber.start();
        ArgumentCaptor<Consumer<GenericEvent>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(nostrClient).subscribe(anyInt(), anyString(), anyList(), callback.capture());
        return callback.getValue();
    }

    private static CashInvoice anInvoice() {
        CashInvoice invoice = new CashInvoice();
        invoice.setRef("inv-123");
        invoice.setRelayUrls("wss://relay.example");
        return invoice;
    }
}
