package xyz.tcheeric.payment.adapter.cash.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.payment.adapter.cash.gateway.service.CashInvoiceService;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.cash.nostr.client.NostrClient;
import xyz.tcheeric.payment.adapter.cash.nostr.crypto.Nip44EncryptionService;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashReceiptStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashReceiptRepository;
import xyz.tcheeric.payment.adapter.webhook.forwarder.GatewayWebhookForwarder;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests for the cash payment flow using mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class CashPaymentE2ETest {

    @Mock
    private CashInvoiceRepository invoiceRepository;

    @Mock
    private CashReceiptRepository receiptRepository;

    @Mock
    private NostrClient nostrClient;

    @Mock
    private GatewayWebhookForwarder webhookForwarder;

    private CashInvoiceStateMachine stateMachine;
    private Nip44EncryptionService encryptionService;
    private CashInvoiceService invoiceService;

    @BeforeEach
    void setUp() {
        stateMachine = new CashInvoiceStateMachine();
        encryptionService = new Nip44EncryptionService();
        invoiceService = new CashInvoiceService(
                invoiceRepository, receiptRepository, stateMachine, nostrClient, encryptionService, webhookForwarder);
        ReflectionTestUtils.setField(invoiceService, "defaultExpiry", 300);
        ReflectionTestUtils.setField(invoiceService, "defaultRelays", "wss://relay.imani.casa,wss://relay.398ja.xyz");
        ReflectionTestUtils.setField(invoiceService, "proofCodeLength", 4);
    }

    // E2E: Create invoice → simulate intent → confirm → verify PAID
    @Test
    void testFullPaymentFlow() {
        // Mock repository saves to return the saved entity
        when(invoiceRepository.existsByRef(anyString())).thenReturn(false);
        when(invoiceRepository.save(any(CashInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(receiptRepository.save(any(CashReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

        // Step 1: Create invoice
        CashInvoice invoice = invoiceService.createInvoice(1500, "USD", "coffee", 300, null);
        assertNotNull(invoice);
        assertEquals(CashInvoiceStatus.PENDING, invoice.getStatus());
        assertNotNull(invoice.getRef());

        String ref = invoice.getRef();

        // Step 2: Simulate intent received
        when(invoiceRepository.findByRef(ref)).thenReturn(Optional.of(invoice));
        invoiceService.recordIntent(ref, "03customer_pubkey", "1234");
        assertEquals(CashInvoiceStatus.INTENT_RECEIVED, invoice.getStatus());

        // Step 3: Confirm receipt
        CashReceipt receipt = invoiceService.confirmReceipt(ref, 1500);
        assertNotNull(receipt);
        assertEquals(ref, receipt.getRef());
        assertEquals(1500, receipt.getAmountReceived());
        assertEquals(CashReceiptStatus.CONFIRMED, receipt.getStatus());
        assertEquals(CashInvoiceStatus.PAID, invoice.getStatus());

        verify(nostrClient, atLeastOnce()).publish(any(), anyList());
    }

    // E2E: Create invoice → cancel → verify CANCELLED
    @Test
    void testCancelFlow() {
        when(invoiceRepository.existsByRef(anyString())).thenReturn(false);
        when(invoiceRepository.save(any(CashInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        // Step 1: Create invoice
        CashInvoice invoice = invoiceService.createInvoice(2000, "EUR", null, 300, null);
        assertNotNull(invoice);
        assertEquals(CashInvoiceStatus.PENDING, invoice.getStatus());

        String ref = invoice.getRef();

        // Step 2: Cancel
        when(invoiceRepository.findByRef(ref)).thenReturn(Optional.of(invoice));
        invoiceService.cancelInvoice(ref, "cash.cancelled_by_merchant");
        assertEquals(CashInvoiceStatus.CANCELLED, invoice.getStatus());
        assertEquals("cash.cancelled_by_merchant", invoice.getCancelReason());
    }

    // E2E: Create invoice → wait for expiry → verify EXPIRED
    @Test
    void testExpiryFlow() {
        when(invoiceRepository.existsByRef(anyString())).thenReturn(false);
        when(invoiceRepository.save(any(CashInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        // Create invoice with very short TTL (already expired)
        CashInvoice invoice = invoiceService.createInvoice(5000, null, null, 300, null);
        assertNotNull(invoice);

        // Manually set expiry to past
        invoice.setExpiresAt(Instant.now().minusSeconds(10));

        // Check expiry
        when(invoiceRepository.findByRef(invoice.getRef())).thenReturn(Optional.of(invoice));
        Optional<CashInvoice> fetched = invoiceService.getInvoiceByRef(invoice.getRef());
        assertTrue(fetched.isPresent());
        assertEquals(CashInvoiceStatus.EXPIRED, fetched.get().getStatus());
    }

    // E2E: Verify cannot confirm an already cancelled invoice
    @Test
    void testCannotConfirmCancelledInvoice() {
        when(invoiceRepository.existsByRef(anyString())).thenReturn(false);
        when(invoiceRepository.save(any(CashInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        CashInvoice invoice = invoiceService.createInvoice(1500, "USD", null, 300, null);
        String ref = invoice.getRef();

        when(invoiceRepository.findByRef(ref)).thenReturn(Optional.of(invoice));
        invoiceService.cancelInvoice(ref, "cash.merchant_request");

        assertThrows(IllegalStateException.class, () -> invoiceService.confirmReceipt(ref, 1500));
    }

    // E2E: Verify direct confirm (without intent) works
    @Test
    void testDirectConfirmWithoutIntent() {
        when(invoiceRepository.existsByRef(anyString())).thenReturn(false);
        when(invoiceRepository.save(any(CashInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(receiptRepository.save(any(CashReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

        CashInvoice invoice = invoiceService.createInvoice(3000, "USD", "meal", 300, null);
        String ref = invoice.getRef();

        when(invoiceRepository.findByRef(ref)).thenReturn(Optional.of(invoice));
        CashReceipt receipt = invoiceService.confirmReceipt(ref, null);

        assertNotNull(receipt);
        assertEquals(3000, receipt.getAmountReceived()); // Should use invoice amount
        assertEquals(CashInvoiceStatus.PAID, invoice.getStatus());
    }
}
