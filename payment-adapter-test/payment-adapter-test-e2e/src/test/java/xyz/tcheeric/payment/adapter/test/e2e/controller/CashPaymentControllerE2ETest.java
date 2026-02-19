package xyz.tcheeric.payment.adapter.test.e2e.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashConfirmRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashReceiptResponse;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CashPaymentControllerE2ETest extends BaseE2ETest {

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies POST /cash/invoice returns 201
    @Test
    void postInvoice_validRequest_returns201() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(100)
                .fiat("USD")
                .build();

        ResponseEntity<CashInvoiceResponse> response =
                restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // Verifies POST /cash/invoice with zero amount returns 400
    @Test
    void postInvoice_zeroAmount_returns400() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(0)
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity("/cash/invoice", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Verifies POST /cash/invoice with negative amount returns 400
    @Test
    void postInvoice_negativeAmount_returns400() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(-100)
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity("/cash/invoice", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Verifies POST /cash/invoice with null amount returns 400
    @Test
    void postInvoice_nullAmount_returns400() {
        CashInvoiceRequest request = CashInvoiceRequest.builder().build();

        ResponseEntity<String> response =
                restTemplate.postForEntity("/cash/invoice", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Verifies GET /cash/invoice/{ref} for non-existent ref returns 404
    @Test
    void getInvoice_nonExistentRef_returns404() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/cash/invoice/doesnotexist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Verifies POST /cash/invoice/{ref}/confirm on non-existent returns 404
    @Test
    void confirmInvoice_nonExistentRef_returns404() {
        ResponseEntity<String> response =
                restTemplate.postForEntity("/cash/invoice/missing/confirm", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Verifies POST /cash/invoice/{ref}/confirm on PAID invoice returns 409
    @Test
    void confirmInvoice_alreadyPaid_returns409() {
        String ref = createAndConfirmInvoice();

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm",
                        CashConfirmRequest.builder().amountReceived(100).build(), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // Verifies POST /cash/invoice/{ref}/cancel on PAID invoice returns 409
    @Test
    void cancelInvoice_alreadyPaid_returns409() {
        String ref = createAndConfirmInvoice();

        ResponseEntity<Void> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/cancel", null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // Verifies that creating multiple invoices assigns unique refs
    @Test
    void postInvoice_multipleCreates_uniqueRefs() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(100)
                .build();

        String ref1 = restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class)
                .getBody().getRef();
        String ref2 = restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class)
                .getBody().getRef();

        assertThat(ref1).isNotEqualTo(ref2);
    }

    // Verifies that invoice response contains relay URLs
    @Test
    void postInvoice_response_containsRelayUrls() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(100)
                .relayUrls(List.of("wss://relay.test.local"))
                .build();

        CashInvoiceResponse body = restTemplate.postForEntity(
                "/cash/invoice", request, CashInvoiceResponse.class).getBody();

        assertThat(body.getRelayUrls()).isNotNull();
        assertThat(body.getRelayUrls()).contains("wss://relay.test.local");
    }

    // Verifies that invoice with invalid fiat length returns 400
    @Test
    void postInvoice_invalidFiatLength_returns400() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(100)
                .fiat("TOOLONG")
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

    private String createAndConfirmInvoice() {
        String ref = createTestInvoice();
        restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", null, CashReceiptResponse.class);
        return ref;
    }
}
