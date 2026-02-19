package xyz.tcheeric.payment.adapter.core.model.entity.enums;

/**
 * Status enum for cash receipt records.
 */
public enum CashReceiptStatus {
    /**
     * Receipt confirmed - cash received
     */
    CONFIRMED,

    /**
     * Receipt disputed by either party
     */
    DISPUTED
}
