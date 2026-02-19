package xyz.tcheeric.payment.adapter.test.e2e.flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashConfirmRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceRequest;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashInvoiceResponse;
import xyz.tcheeric.payment.adapter.cash.gateway.dto.CashReceiptResponse;
import xyz.tcheeric.payment.adapter.cash.gateway.subscriber.CashEventSubscriber;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;

import static org.assertj.core.api.Assertions.assertThat;

class CashInvoiceFullRoundTripE2ETest extends BaseE2ETest {

    @Autowired
    private CashEventSubscriber eventSubscriber;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // Verifies the full round trip: create invoice -> record intent -> confirm -> PAID
    @Test
    void fullRoundTrip_createIntentConfirm_endsInPaid() {
        // Create invoice
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(2500)
                .fiat("EUR")
                .memo("Full round trip test")
                .build();

        CashInvoiceResponse created = restTemplate.postForEntity(
                "/cash/invoice", request, CashInvoiceResponse.class).getBody();
        String ref = created.getRef();
        assertThat(created.getStatus()).isEqualTo(CashInvoiceStatus.PENDING);

        // Simulate intent received via subscriber
        eventSubscriber.handleIntent(ref, "02" + "c".repeat(64), "1234");

        // Verify status is INTENT_RECEIVED
        CashInvoiceResponse afterIntent = restTemplate.getForObject(
                "/cash/invoice/" + ref, CashInvoiceResponse.class);
        assertThat(afterIntent.getStatus()).isEqualTo(CashInvoiceStatus.INTENT_RECEIVED);

        // Confirm receipt
        CashConfirmRequest confirm = CashConfirmRequest.builder()
                .amountReceived(2500)
                .build();
        ResponseEntity<CashReceiptResponse> receiptResponse =
                restTemplate.postForEntity("/cash/invoice/" + ref + "/confirm", confirm, CashReceiptResponse.class);

        assertThat(receiptResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receiptResponse.getBody().getAmountReceived()).isEqualTo(2500);

        // Verify final status is PAID
        CashInvoiceResponse finalState = restTemplate.getForObject(
                "/cash/invoice/" + ref, CashInvoiceResponse.class);
        assertThat(finalState.getStatus()).isEqualTo(CashInvoiceStatus.PAID);
        assertThat(finalState.getPaidAt()).isNotNull();
    }

    // Verifies round trip with intent followed by cancel
    @Test
    void roundTrip_intentThenCancel_endsInCancelled() {
        CashInvoiceRequest request = CashInvoiceRequest.builder()
                .amount(1000)
                .fiat("USD")
                .build();

        CashInvoiceResponse created = restTemplate.postForEntity(
                "/cash/invoice", request, CashInvoiceResponse.class).getBody();
        String ref = created.getRef();

        // Record intent
        eventSubscriber.handleIntent(ref, "02" + "d".repeat(64), null);

        // Cancel
        restTemplate.postForEntity("/cash/invoice/" + ref + "/cancel", null, Void.class);

        CashInvoiceResponse finalState = restTemplate.getForObject(
                "/cash/invoice/" + ref, CashInvoiceResponse.class);
        assertThat(finalState.getStatus()).isEqualTo(CashInvoiceStatus.CANCELLED);
    }
}
