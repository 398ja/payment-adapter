package xyz.tcheeric.payment.adapter.test.e2e.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashCancelRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashConfirmRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashReceiptResponse;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CashErrorHandlingE2ETest extends BaseE2ETest {

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies that confirming a cancelled invoice returns 409
    @Test
    void confirmInvoice_cancelledInvoice_returns409() {
        String ref = createTestInvoice();
        restTemplate.postForEntity("/cash/invoice/" + ref + "/cancel", null, Void.class);

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // Verifies that cancelling a cancelled invoice returns 409
    @Test
    void cancelInvoice_alreadyCancelled_returns409() {
        String ref = createTestInvoice();
        restTemplate.postForEntity("/cash/invoice/" + ref + "/cancel", null, Void.class);

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/cancel", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // Verifies that confirming with wrong HTTP method returns 405
    @Test
    void confirmInvoice_getMethod_returns405() {
        String ref = createTestInvoice();

        ResponseEntity<String> response =
                restTemplate.getForEntity("/cash/invoice/" + ref + "/confirm", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // Verifies that double-confirming a paid invoice returns 409
    @Test
    void confirmInvoice_doubleConfirm_returns409() {
        String ref = createTestInvoice();
        restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", null, CashReceiptResponse.class);

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm",
                        CashConfirmRequest.builder().amountReceived(1000).build(), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // Verifies that confirming an expired invoice returns 409
    @Test
    void confirmInvoice_expired_returns409() {
        String ref = createTestInvoice();
        CashInvoice invoice = invoiceRepository.findByRef(ref).orElseThrow();
        invoice.setStatus(CashInvoiceStatus.EXPIRED);
        invoiceRepository.save(invoice);

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // Verifies that memo exceeding max length returns 400
    @Test
    void postInvoice_memoTooLong_returns400() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(100)
                .memo("x".repeat(257))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity("/cash/invoice", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Verifies that TTL below minimum (60s) returns 400
    @Test
    void postInvoice_ttlBelowMinimum_returns400() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(100)
                .ttlSeconds(30)
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity("/cash/invoice", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
