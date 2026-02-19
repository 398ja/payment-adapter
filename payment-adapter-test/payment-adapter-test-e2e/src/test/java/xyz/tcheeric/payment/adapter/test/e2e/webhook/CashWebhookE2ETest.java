package xyz.tcheeric.payment.adapter.test.e2e.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.cash.gateway.subscriber.CashEventSubscriber;
import xyz.tcheeric.payment.adapter.core.model.entity.CashIntent;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CashWebhookE2ETest extends BaseE2ETest {

    @Autowired
    private CashEventSubscriber eventSubscriber;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies that simulating an intent via subscriber updates invoice status
    @Test
    void simulateIntent_updatesInvoiceToIntentReceived() {
        String ref = createTestInvoice();

        eventSubscriber.handleIntent(ref, "02" + "a".repeat(64), "1234");

        CashInvoiceResponse invoice = restTemplate.getForObject(
                "/cash/invoice/" + ref, CashInvoiceResponse.class);

        assertThat(invoice.getStatus()).isEqualTo(CashInvoiceStatus.INTENT_RECEIVED);
        assertThat(invoice.getIntentReceivedAt()).isNotNull();
    }

    // Verifies that simulating a cancel via subscriber updates invoice status
    @Test
    void simulateCancel_updatesInvoiceToCancelled() {
        String ref = createTestInvoice();

        eventSubscriber.handleCancel(ref, "customer_cancelled");

        CashInvoiceResponse invoice = restTemplate.getForObject(
                "/cash/invoice/" + ref, CashInvoiceResponse.class);

        assertThat(invoice.getStatus()).isEqualTo(CashInvoiceStatus.CANCELLED);
    }

    // Verifies that intent for non-existent invoice is handled gracefully
    @Test
    void simulateIntent_nonExistentRef_handledGracefully() {
        // Should not throw - subscriber catches the error
        eventSubscriber.handleIntent("nonexistent", "02" + "b".repeat(64), "5678");

        // No crash, no data persisted
        ResponseEntity<String> response =
                restTemplate.getForEntity("/cash/invoice/nonexistent", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String createTestInvoice() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(1000)
                .fiat("USD")
                .build();
        return restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class)
                .getBody().getRef();
    }
}
