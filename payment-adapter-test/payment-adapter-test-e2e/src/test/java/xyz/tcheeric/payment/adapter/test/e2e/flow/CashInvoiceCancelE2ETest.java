package xyz.tcheeric.payment.adapter.test.e2e.flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashCancelRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import static org.assertj.core.api.Assertions.assertThat;

class CashInvoiceCancelE2ETest extends BaseE2ETest {

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies that cancelling a pending invoice returns 204
    @Test
    void cancelInvoice_pendingInvoice_returns204() {
        String ref = createTestInvoice();

        CashCancelRequest cancel = CashCancelRequest.builder()
                .reason("customer_changed_mind")
                .build();

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/cancel", cancel, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // Verifies that cancelled invoice shows CANCELLED status
    @Test
    void getInvoice_afterCancel_showsCancelledStatus() {
        String ref = createTestInvoice();

        restTemplate.postForEntity("/cash/invoice/" + ref + "/cancel",
                CashCancelRequest.builder().reason("test").build(), Void.class);

        CashInvoiceResponse invoice = restTemplate.getForObject(
                "/cash/invoice/" + ref, CashInvoiceResponse.class);

        assertThat(invoice.getStatus()).isEqualTo(CashInvoiceStatus.CANCELLED);
    }

    // Verifies that cancelling without body uses default reason
    @Test
    void cancelInvoice_withoutBody_uses204() {
        String ref = createTestInvoice();

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/cancel", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // Verifies that cancelling a non-existent invoice returns 404
    @Test
    void cancelInvoice_nonExistentRef_returns404() {
        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/nonexistent/cancel",
                        CashCancelRequest.builder().reason("test").build(), Void.class);

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
