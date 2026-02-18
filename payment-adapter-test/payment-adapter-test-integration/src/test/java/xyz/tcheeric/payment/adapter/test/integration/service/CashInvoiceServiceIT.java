package xyz.tcheeric.payment.adapter.test.integration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import xyz.tcheeric.payment.adapter.cash.gateway.service.CashInvoiceService;
import xyz.tcheeric.payment.adapter.cash.gateway.state.CashInvoiceStateMachine;
import xyz.tcheeric.payment.adapter.cash.nostr.client.NostrClient;
import xyz.tcheeric.payment.adapter.cash.nostr.crypto.Nip44EncryptionService;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.CashReceipt;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.CashInvoiceRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.CashReceiptRepository;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;
import xyz.tcheeric.payment.adapter.test.integration.TestDataFactory;
import xyz.tcheeric.payment.adapter.webhook.forwarder.GatewayWebhookForwarder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;

@Import({CashInvoiceService.class, CashInvoiceStateMachine.class, Nip44EncryptionService.class})
class CashInvoiceServiceIT extends BasePostgresIT {

    @MockBean
    private NostrClient nostrClient;

    @MockBean
    private GatewayWebhookForwarder webhookForwarder;

    @Autowired
    private CashInvoiceService invoiceService;

    @Autowired
    private CashInvoiceRepository invoiceRepository;

    @Autowired
    private CashReceiptRepository receiptRepository;

    @BeforeEach
    void cleanUp() {
        receiptRepository.deleteAll();
        invoiceRepository.deleteAll();
        doNothing().when(nostrClient).publish(any(), anyList());
    }

    // Verifies that createInvoice persists with PENDING status and generates ref/proofCode
    @Test
    void createInvoice_persistsWithPendingStatus() {
        CashInvoice invoice = invoiceService.createInvoice(1000, "USD", "Test", null, null);

        assertThat(invoice.getId()).isNotNull();
        assertThat(invoice.getRef()).isNotNull().hasSizeBetween(4, 24);
        assertThat(invoice.getStatus()).isEqualTo(CashInvoiceStatus.PENDING);
        assertThat(invoice.getProofCode()).isNotNull();
        assertThat(invoice.getEphemeralPubkey()).isNotNull();
    }

    // Verifies that createInvoice with custom TTL sets correct expiry
    @Test
    void createInvoice_withCustomTtl_setsCorrectExpiry() {
        CashInvoice invoice = invoiceService.createInvoice(500, "EUR", "Test", 600, null);

        Instant expectedMin = Instant.now().plusSeconds(590);
        Instant expectedMax = Instant.now().plusSeconds(610);
        assertThat(invoice.getExpiresAt()).isBetween(expectedMin, expectedMax);
    }

    // Verifies that createInvoice with custom relay URLs persists them
    @Test
    void createInvoice_withCustomRelays_persistsRelayUrls() {
        List<String> relays = List.of("wss://custom.relay.com", "wss://other.relay.com");
        CashInvoice invoice = invoiceService.createInvoice(100, null, null, null, relays);

        assertThat(invoice.getRelayUrls()).contains("wss://custom.relay.com");
        assertThat(invoice.getRelayUrls()).contains("wss://other.relay.com");
    }

    // Verifies that getInvoiceByRef returns saved invoice
    @Test
    void getInvoiceByRef_existingRef_returnsInvoice() {
        CashInvoice created = invoiceService.createInvoice(1000, "USD", "Test", null, null);

        Optional<CashInvoice> found = invoiceService.getInvoiceByRef(created.getRef());

        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualTo(1000);
    }

    // Verifies that getInvoiceByRef auto-expires past-due invoices
    @Test
    void getInvoiceByRef_expiredInvoice_autoExpires() {
        CashInvoice invoice = TestDataFactory.createInvoiceWithStatus(CashInvoiceStatus.PENDING);
        invoice.setExpiresAt(Instant.now().minusSeconds(10));
        invoiceRepository.save(invoice);

        Optional<CashInvoice> found = invoiceService.getInvoiceByRef(invoice.getRef());

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(CashInvoiceStatus.EXPIRED);
    }

    // Verifies that confirmReceipt transitions to PAID and creates receipt
    @Test
    void confirmReceipt_pendingInvoice_transitionsToPaid() {
        CashInvoice invoice = invoiceService.createInvoice(1000, "USD", "Test", null, null);

        CashReceipt receipt = invoiceService.confirmReceipt(invoice.getRef(), 1000);

        assertThat(receipt.getRef()).isEqualTo(invoice.getRef());
        assertThat(receipt.getAmountReceived()).isEqualTo(1000);

        CashInvoice updated = invoiceRepository.findByRef(invoice.getRef()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CashInvoiceStatus.PAID);
        assertThat(updated.getPaidAt()).isNotNull();
    }

    // Verifies that confirmReceipt with null amount uses invoice amount
    @Test
    void confirmReceipt_nullAmount_usesInvoiceAmount() {
        CashInvoice invoice = invoiceService.createInvoice(750, "USD", "Test", null, null);

        CashReceipt receipt = invoiceService.confirmReceipt(invoice.getRef(), null);

        assertThat(receipt.getAmountReceived()).isEqualTo(750);
    }

    // Verifies that confirmReceipt on already paid invoice throws
    @Test
    void confirmReceipt_alreadyPaid_throwsIllegalState() {
        CashInvoice invoice = invoiceService.createInvoice(1000, "USD", "Test", null, null);
        invoiceService.confirmReceipt(invoice.getRef(), 1000);

        assertThatThrownBy(() -> invoiceService.confirmReceipt(invoice.getRef(), 1000))
                .isInstanceOf(IllegalStateException.class);
    }

    // Verifies that cancelInvoice transitions to CANCELLED
    @Test
    void cancelInvoice_pendingInvoice_transitionsToCancelled() {
        CashInvoice invoice = invoiceService.createInvoice(1000, "USD", "Test", null, null);

        invoiceService.cancelInvoice(invoice.getRef(), "customer_request");

        CashInvoice updated = invoiceRepository.findByRef(invoice.getRef()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CashInvoiceStatus.CANCELLED);
        assertThat(updated.getCancelReason()).isEqualTo("customer_request");
    }

    // Verifies that cancelInvoice on already paid invoice throws
    @Test
    void cancelInvoice_alreadyPaid_throwsIllegalState() {
        CashInvoice invoice = invoiceService.createInvoice(1000, "USD", "Test", null, null);
        invoiceService.confirmReceipt(invoice.getRef(), 1000);

        assertThatThrownBy(() -> invoiceService.cancelInvoice(invoice.getRef(), "test"))
                .isInstanceOf(IllegalStateException.class);
    }

    // Verifies that recordIntent transitions to INTENT_RECEIVED
    @Test
    void recordIntent_pendingInvoice_transitionsToIntentReceived() {
        CashInvoice invoice = invoiceService.createInvoice(1000, "USD", "Test", null, null);

        invoiceService.recordIntent(invoice.getRef(), "02" + "c".repeat(64), "1234");

        CashInvoice updated = invoiceRepository.findByRef(invoice.getRef()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CashInvoiceStatus.INTENT_RECEIVED);
        assertThat(updated.getIntentReceivedAt()).isNotNull();
    }
}
