package xyz.tcheeric.payment.adapter.cash.nostr.event;

/**
 * Event kind constants for NIP-XX Cash Payments.
 * These event kinds are in the experimental range (5200-5204).
 */
public final class CashEventKind {

    private CashEventKind() {
        // Utility class
    }

    /**
     * CashInvoice: Merchant publishes invoice to relays (M → relays)
     */
    public static final int CASH_INVOICE = 5200;

    /**
     * CashIntent: Customer signals intent to pay (C → M)
     */
    public static final int CASH_INTENT = 5201;

    /**
     * CashReceipt: Merchant confirms cash received (M → C)
     */
    public static final int CASH_RECEIPT = 5202;

    /**
     * CashCancel: Either party cancels the transaction (M ↔ C)
     */
    public static final int CASH_CANCEL = 5203;

    /**
     * CashDispute: Optional dispute record (M ↔ C)
     */
    public static final int CASH_DISPUTE = 5204;
}
