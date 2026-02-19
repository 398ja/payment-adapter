package xyz.tcheeric.payment.adapter.core.model.entity.enums;

/**
 * Status enum for cash invoices following the NIP-XX Cash Payments specification.
 * Represents the state machine for cash payment flows.
 */
public enum CashInvoiceStatus {
    /**
     * Invoice created but not yet published to relays
     */
    CREATED,

    /**
     * Invoice published to relays, waiting for customer intent
     */
    PENDING,

    /**
     * Customer's intent (kind 5201) received, awaiting cash exchange
     */
    INTENT_RECEIVED,

    /**
     * Cash received and confirmed, transaction complete
     */
    PAID,

    /**
     * Invoice expired (TTL exceeded)
     */
    EXPIRED,

    /**
     * Invoice cancelled by merchant or customer
     */
    CANCELLED
}
