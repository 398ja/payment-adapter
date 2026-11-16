package xyz.tcheeric.payment.adapter.cash.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import nostr.base.PublicKey;
import nostr.event.impl.GenericEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.cash.nostr.client.NostrClient;
import xyz.tcheeric.payment.adapter.cash.nostr.crypto.Nip44EncryptionService;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashReceiptRepository;
import xyz.tcheeric.payment.adapter.webhook.forwarder.GatewayWebhookForwarder;

/** imani-gateway-customer#310: cash invoice/receipt publishing must fail closed on encryption errors. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashInvoiceServiceFailClosedTest {

    @Mock private CashInvoiceRepository invoices;
    @Mock private CashReceiptRepository receipts;
    @Mock private NostrClient nostr;
    @Mock private Nip44EncryptionService encryption;
    @Mock private GatewayWebhookForwarder forwarder;

    private CashInvoiceService service;

    @BeforeEach
    void setUp() {
        service = new CashInvoiceService(invoices, receipts, new CashInvoiceStateMachine(), nostr,
                encryption, forwarder);
        ReflectionTestUtils.setField(service, "defaultExpiry", 300);
        ReflectionTestUtils.setField(service, "defaultRelays", "wss://relay.example");
        ReflectionTestUtils.setField(service, "proofCodeLength", 4);
        when(invoices.existsByRef(anyString())).thenReturn(false);
        when(invoices.save(any(CashInvoice.class))).thenAnswer(i -> i.getArgument(0));
        when(receipts.save(any(CashReceipt.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void createInvoiceEncryptionFailureDoesNotPublishOrPersist() {
        RuntimeException boom = new RuntimeException("nip44 failure");
        when(encryption.encrypt(anyString(), anyString(), any(PublicKey.class))).thenThrow(boom);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.createInvoice(1500, "XAF", "memo", 300, null));

        assertSame(boom, ex.getCause());
        verify(nostr, never()).publish(any(), anyList());
        verify(invoices, never()).save(any());
    }

    @Test
    void createInvoiceHappyPathPublishesEncryptedContent() {
        when(encryption.encrypt(anyString(), anyString(), any(PublicKey.class))).thenReturn("CIPHERTEXT");

        CashInvoice invoice = service.createInvoice(1500, "XAF", "memo", 300, null);

        ArgumentCaptor<GenericEvent> captor = ArgumentCaptor.forClass(GenericEvent.class);
        verify(nostr).publish(captor.capture(), anyList());
        assertEquals("CIPHERTEXT", captor.getValue().getContent());
        assertEquals(CashInvoiceStatus.PENDING, invoice.getStatus());
        verify(invoices).save(invoice);
    }

    @Test
    void confirmReceiptEncryptionFailureDoesNotPublishOrPersist() {
        when(encryption.encrypt(anyString(), anyString(), any(PublicKey.class))).thenReturn("CIPHERTEXT");
        CashInvoice invoice = service.createInvoice(1500, "XAF", null, 300, List.of("wss://relay.example"));
        when(invoices.findByRef(invoice.getRef())).thenReturn(Optional.of(invoice));
        org.mockito.Mockito.clearInvocations(nostr, invoices);
        when(encryption.encrypt(anyString(), anyString(), any(PublicKey.class)))
                .thenThrow(new RuntimeException("nip44 failure"));

        assertThrows(IllegalStateException.class, () -> service.confirmReceipt(invoice.getRef(), 1500));

        verify(nostr, never()).publish(any(), anyList());
        verify(invoices, never()).save(any());
        verify(receipts, never()).save(any());
        assertNotEquals(CashInvoiceStatus.PAID, invoice.getStatus());
    }
}
