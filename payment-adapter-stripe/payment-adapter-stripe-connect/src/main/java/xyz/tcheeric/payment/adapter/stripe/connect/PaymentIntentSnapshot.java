package xyz.tcheeric.payment.adapter.stripe.connect;

/**
 * The few facts about a PaymentIntent that verifying a card reference needs, read on the connected
 * account that owns it.
 *
 * @param id       the {@code pi_} id Stripe returned, compared against the one asked for
 * @param status   Stripe's status, {@code succeeded} once the money moved
 * @param amount   in the currency's minor units
 * @param currency lower case, as Stripe spells it
 * @param livemode whether this was a real payment
 */
public record PaymentIntentSnapshot(String id, String status, long amount, String currency, boolean livemode) {
}
