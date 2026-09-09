package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

/**
 * Whether a coupon was really issued against a payment request.
 *
 * <p>Three answers, never two, and the third is the one that matters.
 * {@code imani-woo} ADR 0009 is explicit that an unreachable gateway means
 * <em>unknown</em> rather than <em>unfulfilled</em>: a network problem must
 * never discharge a debt, and must never accuse anyone of not having issued.
 *
 * <p>Collapsing UNKNOWN into NOT_FULFILLED would make a gateway outage look
 * exactly like an issuer reporting success it never achieved, and the two need
 * opposite responses — wait and retry, versus investigate.
 */
public enum Fulfilment {

    /** A COMPLETED send answered the request. The debt may be discharged. */
    FULFILLED,

    /**
     * The gateway answered, and no completed send exists.
     *
     * <p>Not "not yet": the caller is claiming it has already issued, so this
     * is a discharge that must be refused. The debt stands.
     */
    NOT_FULFILLED,

    /**
     * The gateway could not be asked.
     *
     * <p>Neither a discharge nor an accusation. The caller should try again.
     */
    UNKNOWN
}
