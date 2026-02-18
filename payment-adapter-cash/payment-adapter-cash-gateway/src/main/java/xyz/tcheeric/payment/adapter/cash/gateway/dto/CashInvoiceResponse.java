package xyz.tcheeric.payment.adapter.cash.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.payment.adapter.core.model.entity.CashInvoice;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.CashInvoiceStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Response DTO for cash invoice operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashInvoiceResponse {

    /**
     * Unique invoice reference.
     */
    private String ref;

    /**
     * Amount in minor currency units.
     */
    private Integer amount;

    /**
     * ISO 4217 currency code (null if satoshis).
     */
    private String fiat;

    /**
     * Invoice memo/description.
     */
    private String memo;

    /**
     * Current invoice status.
     */
    private CashInvoiceStatus status;

    /**
     * Merchant's ephemeral public key (hex).
     */
    private String merchantPubkey;

    /**
     * Proof code for customer verification (shown to merchant only).
     */
    private String proofCode;

    /**
     * Customer's Nostr public key (set after intent received).
     */
    private String customerPubkey;

    /**
     * Invoice expiration time.
     */
    private Instant expiresAt;

    /**
     * Relay URLs for event distribution.
     */
    private List<String> relayUrls;

    /**
     * QR code payload URI (nostr+cash://pay?...).
     */
    private String qrPayload;

    /**
     * QR code as base64-encoded PNG data URI.
     */
    private String qrDataUri;

    /**
     * Invoice creation time.
     */
    private Instant createdAt;

    /**
     * Time when invoice was published to relays.
     */
    private Instant publishedAt;

    /**
     * Time when intent was received from customer.
     */
    private Instant intentReceivedAt;

    /**
     * Time when payment was confirmed.
     */
    private Instant paidAt;

    /**
     * Create response from CashInvoice entity.
     */
    public static CashInvoiceResponse fromEntity(CashInvoice invoice) {
        return CashInvoiceResponse.builder()
                .ref(invoice.getRef())
                .amount(invoice.getAmount())
                .fiat(invoice.getFiat())
                .memo(invoice.getMemo())
                .status(invoice.getStatus())
                .merchantPubkey(invoice.getEphemeralPubkey())
                .proofCode(invoice.getProofCode())
                .customerPubkey(invoice.getCustomerPubkey())
                .expiresAt(invoice.getExpiresAt())
                .relayUrls(invoice.getRelayUrls() != null
                        ? Arrays.asList(invoice.getRelayUrls().split(","))
                        : null)
                .createdAt(invoice.getCreatedAt())
                .publishedAt(invoice.getPublishedAt())
                .intentReceivedAt(invoice.getIntentReceivedAt())
                .paidAt(invoice.getPaidAt())
                .build();
    }

    /**
     * Create response with QR code data.
     */
    public static CashInvoiceResponse fromEntityWithQr(CashInvoice invoice, String qrPayload, String qrDataUri) {
        CashInvoiceResponse response = fromEntity(invoice);
        response.setQrPayload(qrPayload);
        response.setQrDataUri(qrDataUri);
        return response;
    }
}
