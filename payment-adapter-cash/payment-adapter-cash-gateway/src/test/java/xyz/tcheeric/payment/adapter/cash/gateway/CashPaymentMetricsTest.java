package xyz.tcheeric.payment.adapter.cash.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.tcheeric.payment.adapter.cash.gateway.service.CashInvoiceService;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.cash.nostr.client.NostrClient;
import xyz.tcheeric.payment.adapter.cash.nostr.crypto.Nip44EncryptionService;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashReceiptRepository;
import xyz.tcheeric.payment.adapter.webhook.forwarder.GatewayWebhookForwarder;

/** imani-deploy#210: a cash invoice is counted when created, confirmed, cancelled and expired. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashPaymentMetricsTest {

    @Mock private CashInvoiceRepository invoices;
    @Mock private CashReceiptRepository receipts;
    @Mock private NostrClient nostr;
    @Mock private GatewayWebhookForwarder forwarder;

    private SimpleMeterRegistry registry;
    private CashInvoiceService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new CashInvoiceService(invoices, receipts, new CashInvoiceStateMachine(), nostr,
                new Nip44EncryptionService(), forwarder);
        service.setMeterRegistry(registry);
        ReflectionTestUtils.setField(service, "defaultExpiry", 300);
        ReflectionTestUtils.setField(service, "defaultRelays", "wss://relay.example");
        ReflectionTestUtils.setField(service, "proofCodeLength", 4);
        when(invoices.existsByRef(anyString())).thenReturn(false);
        when(invoices.save(any(CashInvoice.class))).thenAnswer(i -> i.getArgument(0));
        when(receipts.save(any(CashReceipt.class))).thenAnswer(i -> i.getArgument(0));
    }

    private double count(String status, String reason) {
        return registry.find("imani.payments").tag("source", "cash").tag("status", status).tag("reason", reason)
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    void aConfirmedSaleCountsInitiatedThenSucceededWithItsAmount() {
        CashInvoice invoice = service.createInvoice(1500, "XAF", null, 300, null);
        when(invoices.findByRef(invoice.getRef())).thenReturn(Optional.of(invoice));

        service.confirmReceipt(invoice.getRef(), 1500);

        assertEquals(1.0, count("initiated", "none"));
        assertEquals(1.0, count("succeeded", "none"));
        assertEquals(1500.0, registry.get("imani.payment.amount.minor").tag("status", "succeeded")
                .tag("currency", "XAF").counter().count());
        assertEquals(1L, registry.get("imani.payment.duration").tag("stage", "invoice_to_confirm").timer().count());
    }

    @Test
    void aCancelIsCountedWithWhoCancelled() {
        CashInvoice merchant = service.createInvoice(1000, "XAF", null, 300, null);
        when(invoices.findByRef(merchant.getRef())).thenReturn(Optional.of(merchant));
        service.cancelInvoice(merchant.getRef(), "cash.cancelled_by_merchant");

        CashInvoice customer = service.createInvoice(1000, "XAF", null, 300, null);
        when(invoices.findByRef(customer.getRef())).thenReturn(Optional.of(customer));
        service.cancelInvoice(customer.getRef(), "cash.cancelled_by_customer");

        assertEquals(1.0, count("cancelled", "cancelled_by_merchant"));
        assertEquals(1.0, count("cancelled", "cancelled_by_customer"));
        assertEquals(0.0, count("succeeded", "none"));
    }

    @Test
    void anExpiryIsACancellationWithReasonExpired() {
        CashInvoice invoice = service.createInvoice(1000, "XAF", null, 300, null);
        service.recordExpired(invoice);
        assertEquals(1.0, count("cancelled", "expired"));
    }
}
