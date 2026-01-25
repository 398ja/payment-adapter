package xyz.tcheeric.payment.adapter.core.common;

/**
 * Enum representing different payment types supported by the adapter.
 */
public enum PaymentType {
    LIGHTNING_NETWORK("ln", "Lightning Network payments"),
    CASH("cash", "Cash payments"),
    CREDIT_CARD("cc", "Credit card payments"),
    BANK_TRANSFER("bt", "Bank transfer payments");

    private final String code;
    private final String description;

    PaymentType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Find PaymentType by its code
     * @param code the code to search for
     * @return the matching PaymentType
     * @throws IllegalArgumentException if no matching PaymentType is found
     */
    public static PaymentType fromCode(String code) {
        for (PaymentType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown payment type code: " + code);
    }
}
