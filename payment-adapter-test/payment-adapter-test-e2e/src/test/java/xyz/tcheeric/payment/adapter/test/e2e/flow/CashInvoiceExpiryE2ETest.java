package xyz.tcheeric.payment.adapter.test.e2e.flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CashInvoiceExpiryE2ETest extends BaseE2ETest {

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies that retrieving an expired invoice auto-transitions to EXPIRED
    @Test
    void getInvoice_expiredInvoice_autoTransitionsToExpired() {
        // Create invoice and manually backdate its expiry
        String ref = createTestInvoice();
        CashInvoice invoice = invoiceRepository.findByRef(ref).orElseThrow();
        invoice.setExpiresAt(Instant.now().minusSeconds(10));
        invoiceRepository.save(invoice);

        ResponseEntity<CashInvoiceResponse> response =
                restTemplate.getForEntity("/cash/invoice/" + ref, CashInvoiceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getStatus()).isEqualTo(CashInvoiceStatus.EXPIRED);
    }

    // Verifies that confirming an expired invoice returns 409 Conflict
    @Test
    void confirmInvoice_expiredInvoice_returns409() {
        String ref = createTestInvoice();
        CashInvoice invoice = invoiceRepository.findByRef(ref).orElseThrow();
        invoice.setExpiresAt(Instant.now().minusSeconds(10));
        invoice.setStatus(CashInvoiceStatus.EXPIRED);
        invoiceRepository.save(invoice);

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private String createTestInvoice() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(500)
                .fiat("USD")
                .build();
        return restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class)
                .getBody().getRef();
    }
}
