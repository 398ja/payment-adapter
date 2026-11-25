package xyz.tcheeric.payment.adapter.paypal.gateway.model;

/**
 * A dispute against a capture, as PayPal lists it.
 *
 * @param id     PayPal's dispute id ({@code PP-D-…})
 * @param status {@code OPEN}, {@code WAITING_FOR_BUYER_RESPONSE}, {@code WAITING_FOR_SELLER_RESPONSE},
 *               {@code UNDER_REVIEW}, {@code RESOLVED} or {@code OTHER}
 */
public record PaypalDispute(String id, String status) {

    /** Still contested. A resolved dispute's outcome reaches the row by its own webhook. */
    public boolean open() {
        return !"RESOLVED".equalsIgnoreCase(status);
    }
}
