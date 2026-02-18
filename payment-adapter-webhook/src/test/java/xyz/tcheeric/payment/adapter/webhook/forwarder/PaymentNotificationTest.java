package xyz.tcheeric.payment.adapter.webhook.forwarder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PaymentNotification DTO.
 */
class PaymentNotificationTest {

    @Test
    void forBolt11_shouldCreateCorrectNotification() {
        // When
        PaymentNotification notification = PaymentNotification.forBolt11(
                "quote123", 1000, "preimage456");

        // Then
        assertEquals("quote123", notification.getQuoteId());
        assertEquals("bolt11", notification.getPaymentMethod());
        assertEquals(1000, notification.getAmount());
        assertEquals("preimage456", notification.getPreimage());
        assertNull(notification.getReceiptId());
        assertNotNull(notification.getPaidAt());
    }

    @Test
    void forCash_shouldCreateCorrectNotification() {
        // When
        PaymentNotification notification = PaymentNotification.forCash(
                "quote789", 500, "USD", "receipt123", "02aabb");

        // Then
        assertEquals("quote789", notification.getQuoteId());
        assertEquals("cash", notification.getPaymentMethod());
        assertEquals(500, notification.getAmount());
        assertEquals("USD", notification.getUnit());
        assertNull(notification.getPreimage());
        assertEquals("receipt123", notification.getReceiptId());
        assertEquals("02aabb", notification.getCustomerPubkey());
        assertNotNull(notification.getPaidAt());
    }

    @Test
    void getIdempotencyKey_shouldCombineMethodAndQuoteId() {
        // Given
        PaymentNotification notification = PaymentNotification.forBolt11(
                "quote123", 1000, "preimage");

        // When
        String key = notification.getIdempotencyKey();

        // Then
        assertEquals("bolt11:quote123", key);
    }

    @Test
    void builder_shouldCreateNotification() {
        // When
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId("q1")
                .paymentMethod("custom")
                .amount(100)
                .build();

        // Then
        assertEquals("q1", notification.getQuoteId());
        assertEquals("custom", notification.getPaymentMethod());
        assertEquals(100, notification.getAmount());
    }
}
