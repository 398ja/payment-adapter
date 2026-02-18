package xyz.tcheeric.payment.adapter.test.e2e.flow;

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

import static org.assertj.core.api.Assertions.assertThat;

class CashInvoiceHappyPathE2ETest extends BaseE2ETest {

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies that creating an invoice returns 201 with all expected fields
    @Test
    void createInvoice_returns201WithAllFields() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(1000)
                .fiat("USD")
                .memo("Coffee")
                .build();

        ResponseEntity<CashInvoiceResponse> response =
                restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CashInvoiceResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getRef()).isNotNull();
        assertThat(body.getAmount()).isEqualTo(1000);
        assertThat(body.getFiat()).isEqualTo("USD");
        assertThat(body.getStatus()).isEqualTo(CashInvoiceStatus.PENDING);
        assertThat(body.getMerchantPubkey()).isNotNull();
        assertThat(body.getQrPayload()).startsWith("nostr+cash://pay?");
        assertThat(body.getExpiresAt()).isNotNull();
    }

    // Verifies that a created invoice can be retrieved by ref
    @Test
    void getInvoice_afterCreate_returnsInvoice() {
        String ref = createTestInvoice();

        ResponseEntity<CashInvoiceResponse> response =
                restTemplate.getForEntity("/cash/invoice/" + ref, CashInvoiceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRef()).isEqualTo(ref);
    }

    // Verifies that confirming an invoice transitions it to PAID
    @Test
    void confirmInvoice_afterCreate_transitionsToPaid() {
        String ref = createTestInvoice();

        CashConfirmRequest confirm = CashConfirmRequest.builder()
                .amountReceived(1000)
                .build();

        ResponseEntity<CashReceiptResponse> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", confirm, CashReceiptResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAmountReceived()).isEqualTo(1000);

        // Verify status is PAID
        CashInvoiceResponse invoice = restTemplate.getForObject("/cash/invoice/" + ref, CashInvoiceResponse.class);
        assertThat(invoice.getStatus()).isEqualTo(CashInvoiceStatus.PAID);
        assertThat(invoice.getPaidAt()).isNotNull();
    }

    // Verifies that confirm without body uses invoice amount
    @Test
    void confirmInvoice_withoutBody_usesInvoiceAmount() {
        String ref = createTestInvoice();

        ResponseEntity<CashReceiptResponse> response =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", null, CashReceiptResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAmountReceived()).isEqualTo(1000);
    }

    // Verifies that creating invoice without fiat works (satoshis mode)
    @Test
    void createInvoice_withoutFiat_succeeds() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(21000)
                .build();

        ResponseEntity<CashInvoiceResponse> response =
                restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getFiat()).isNull();
        assertThat(response.getBody().getAmount()).isEqualTo(21000);
    }

    // Verifies that creating invoice with custom TTL sets correct expiry
    @Test
    void createInvoice_withCustomTtl_setsExpiry() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(500)
                .ttlSeconds(600)
                .build();

        ResponseEntity<CashInvoiceResponse> response =
                restTemplate.postForEntity("/cash/invoice", request, CashInvoiceResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getExpiresAt()).isNotNull();
    }

    private String createTestInvoice() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(1000)
                .fiat("USD")
                .memo("Test")
                .build();
        CashInvoiceResponse body = restTemplate.postForEntity(
                "/cash/invoice", request, CashInvoiceResponse.class).getBody();
        return body.getRef();
    }
}
