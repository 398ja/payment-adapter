package xyz.tcheeric.payment.adapter.test.e2e.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import static org.assertj.core.api.Assertions.assertThat;

class CashQrCodeE2ETest extends BaseE2ETest {

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies that GET /cash/invoice/{ref}/qr returns a PNG image
    @Test
    void getQrCode_existingInvoice_returnsPng() {
        String ref = createTestInvoice();

        ResponseEntity<byte[]> response =
                restTemplate.getForEntity("/cash/invoice/" + ref + "/qr", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().length).isGreaterThan(0);
    }

    // Verifies that QR code PNG starts with PNG magic bytes
    @Test
    void getQrCode_pngMagicBytes_areCorrect() {
        String ref = createTestInvoice();

        byte[] png = restTemplate.getForObject("/cash/invoice/" + ref + "/qr", byte[].class);

        assertThat(png).isNotNull();
        // PNG magic bytes: 137 80 78 71 13 10 26 10
        assertThat(png[0]).isEqualTo((byte) 0x89);
        assertThat(png[1]).isEqualTo((byte) 0x50); // P
        assertThat(png[2]).isEqualTo((byte) 0x4E); // N
        assertThat(png[3]).isEqualTo((byte) 0x47); // G
    }

    // Verifies that GET /cash/invoice/{ref}/qr-payload returns nostr+cash URI
    @Test
    void getQrPayload_existingInvoice_returnsNostrCashUri() {
        String ref = createTestInvoice();

        ResponseEntity<String> response =
                restTemplate.getForEntity("/cash/invoice/" + ref + "/qr-payload", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("nostr+cash://pay?");
        assertThat(response.getBody()).contains("ref=" + ref);
    }

    // Verifies that QR payload contains required URI parameters
    @Test
    void getQrPayload_containsRequiredParams() {
        String ref = createTestInvoice();

        String payload = restTemplate.getForObject("/cash/invoice/" + ref + "/qr-payload", String.class);

        assertThat(payload).contains("k=");
        assertThat(payload).contains("ref=");
        assertThat(payload).contains("amt=");
        assertThat(payload).contains("exp=");
        assertThat(payload).contains("r=");
    }

    // Verifies that GET /cash/invoice/{ref}/qr returns 404 for non-existent ref
    @Test
    void getQrCode_nonExistentRef_returns404() {
        ResponseEntity<byte[]> response =
                restTemplate.getForEntity("/cash/invoice/nonexistent/qr", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Verifies that GET /cash/invoice/{ref}/qr-payload returns 404 for non-existent ref
    @Test
    void getQrPayload_nonExistentRef_returns404() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/cash/invoice/nonexistent/qr-payload", String.class);

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
