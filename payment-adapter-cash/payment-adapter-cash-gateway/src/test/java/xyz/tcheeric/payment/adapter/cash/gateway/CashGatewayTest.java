package xyz.tcheeric.payment.adapter.cash.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.payment.adapter.cash.gateway.service.CashInvoiceService;
import xyz.tcheeric.payment.adapter.cash.nostr.codec.NostrCashUri;
import xyz.tcheeric.payment.adapter.core.common.PaymentType;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashReceiptStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CashGatewayTest {

    @Mock
    private CashInvoiceService invoiceService;

    private CashGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new CashGateway(invoiceService);
    }

    // Verifies gateway identity methods
    @Test
    void testGatewayIdentity() {
        assertEquals("nostr-cash", gateway.getName());
        assertEquals("cash", gateway.getGatewayId());
        assertEquals(PaymentType.CASH, gateway.getPaymentType());
    }

    // Verifies creating a cash invoice delegates to CashInvoiceService
    @Test
    void testCreateCashInvoice() {
        CashInvoice mockInvoice = createMockInvoice("abc123", 1500, "USD");
        when(invoiceService.createInvoice(eq(1500), eq("USD"), eq("espresso"), eq(300), isNull()))
                .thenReturn(mockInvoice);

        CashInvoice invoice = gateway.createCashInvoice(1500, "USD", "espresso", 300, null);

        assertNotNull(invoice);
        assertEquals("abc123", invoice.getRef());
        assertEquals(1500, invoice.getAmount());
        verify(invoiceService).createInvoice(1500, "USD", "espresso", 300, null);
    }

    // Verifies invoice lookup by ref delegates correctly
    @Test
    void testGetInvoiceByRef() {
        CashInvoice mockInvoice = createMockInvoice("abc123", 1500, "USD");
        when(invoiceService.getInvoiceByRef("abc123")).thenReturn(Optional.of(mockInvoice));

        CashInvoice found = gateway.getInvoiceByRef("abc123");
        assertNotNull(found);
        assertEquals("abc123", found.getRef());
    }

    // Verifies null returned for unknown ref
    @Test
    void testGetInvoiceByRefNotFound() {
        when(invoiceService.getInvoiceByRef("nonexistent")).thenReturn(Optional.empty());

        assertNull(gateway.getInvoiceByRef("nonexistent"));
    }

    // Verifies QR URI generation
    @Test
    void testGetQrUri() {
        CashInvoice mockInvoice = createMockInvoice("abc123", 1500, "USD");
        when(invoiceService.getInvoiceByRef("abc123")).thenReturn(Optional.of(mockInvoice));

        NostrCashUri uri = gateway.getQrUri("abc123");

        assertNotNull(uri);
        assertEquals("abc123", uri.getRef());
        assertEquals(1500, uri.getAmount());
        assertEquals("USD", uri.getFiat());
    }

    // Verifies confirming receipt delegates correctly
    @Test
    void testConfirmCashReceipt() {
        CashReceipt mockReceipt = new CashReceipt();
        mockReceipt.setRef("abc123");
        mockReceipt.setAmountReceived(1500);
        mockReceipt.setEventId("receipt-event-id");
        mockReceipt.setConfirmedAt(Instant.now());
        mockReceipt.setPublishedAt(Instant.now());
        mockReceipt.setStatus(CashReceiptStatus.CONFIRMED);

        when(invoiceService.confirmReceipt("abc123", 1500)).thenReturn(mockReceipt);

        CashReceipt receipt = gateway.confirmCashReceipt("abc123", 1500);

        assertNotNull(receipt);
        assertEquals("abc123", receipt.getRef());
        assertEquals(1500, receipt.getAmountReceived());
    }

    // Verifies cancel delegates correctly
    @Test
    void testCancelInvoice() {
        doNothing().when(invoiceService).cancelInvoice("abc123", "cash.cancelled_by_merchant");

        gateway.cancelCashInvoice("abc123", "cash.cancelled_by_merchant");

        verify(invoiceService).cancelInvoice("abc123", "cash.cancelled_by_merchant");
    }

    // Verifies checkPaymentStatus for paid invoice
    @Test
    void testCheckPaymentStatusPaid() {
        CashInvoice mockInvoice = createMockInvoice("abc123", 1500, "USD");
        mockInvoice.setStatus(CashInvoiceStatus.PAID);
        when(invoiceService.getInvoiceByRef("abc123")).thenReturn(Optional.of(mockInvoice));

        assertTrue(gateway.checkPaymentStatus("abc123"));
    }

    // Verifies checkPaymentStatus for pending invoice
    @Test
    void testCheckPaymentStatusPending() {
        CashInvoice mockInvoice = createMockInvoice("abc123", 1500, "USD");
        when(invoiceService.getInvoiceByRef("abc123")).thenReturn(Optional.of(mockInvoice));

        assertFalse(gateway.checkPaymentStatus("abc123"));
    }

    // Verifies getPaymentPreimage returns receipt event ID
    @Test
    void testGetPaymentPreimage() {
        CashReceipt mockReceipt = new CashReceipt();
        mockReceipt.setEventId("receipt-event-123");
        mockReceipt.setStatus(CashReceiptStatus.CONFIRMED);
        when(invoiceService.getReceiptByRef("abc123")).thenReturn(Optional.of(mockReceipt));

        assertEquals("receipt-event-123", gateway.getPaymentPreimage("abc123"));
    }

    // Verifies getFeeReserve always returns 0 for cash
    @Test
    void testGetFeeReserve() {
        assertEquals(0, gateway.getFeeReserve("any"));
    }

    // Verifies unsupported operations throw
    @Test
    void testUnsupportedOperations() {
        assertThrows(UnsupportedOperationException.class, () -> gateway.createMintQuote(100, "test"));
        assertThrows(UnsupportedOperationException.class, () -> gateway.createMeltQuote(100, "inv", "test"));
        assertThrows(UnsupportedOperationException.class, () -> gateway.createMeltQuote("request"));
        assertThrows(UnsupportedOperationException.class, () -> gateway.pay("request"));
    }

    private CashInvoice createMockInvoice(String ref, int amount, String fiat) {
        CashInvoice invoice = CashInvoice.create(
                ref, "pubkey123", "privkey123", amount, fiat, null,
                Instant.now().plusSeconds(300), "wss://relay.damus.io");
        invoice.setStatus(CashInvoiceStatus.PENDING);
        invoice.setProofCode("1234");
        return invoice;
    }
}
