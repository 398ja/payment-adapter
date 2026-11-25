package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.Map;
import java.util.stream.Collectors;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;

/**
 * One processor's live check, offered to the operator's retry (ADR 0002).
 *
 * <p>A holder, deliberately not a {@link LivePaymentCheck} bean: the Stripe check stays the single
 * bean of that type, so nothing that already asks for one becomes ambiguous when PayPal is on.
 *
 * @param processor {@code paypal}, as on {@link StripePurchase#getProcessor()}
 * @param check     reads that processor's payment live
 */
public record ProcessorLivePaymentCheck(String processor, LivePaymentCheck check) {

    /**
     * Routes each row to its own processor's live read, so a PayPal row is never read from Stripe.
     * A processor with no check configured fails closed, as {@link LivePaymentCheck#unconfigured()}.
     */
    public static LivePaymentCheck routed(LivePaymentCheck stripe, Iterable<ProcessorLivePaymentCheck> others) {
        Map<String, LivePaymentCheck> byProcessor = new java.util.HashMap<>();
        for (ProcessorLivePaymentCheck other : others) {
            byProcessor.put(other.processor(), other.check());
        }
        // Always routed, even with no other processor: a PayPal row left behind after PayPal is
        // switched off must fail closed, never be read from Stripe.
        Map<String, LivePaymentCheck> routes = Map.copyOf(byProcessor);
        return purchase -> {
            String processor = purchase.getProcessor() == null ? StripePurchase.PROCESSOR_STRIPE : purchase.getProcessor();
            if (StripePurchase.PROCESSOR_STRIPE.equals(processor)) {
                return stripe.refresh(purchase);
            }
            LivePaymentCheck check = routes.get(processor);
            if (check == null) {
                throw new LivePaymentUnavailableException(processor + " is not enabled, so the payment of "
                        + purchase.getEventId() + " cannot be read live (configured: "
                        + routes.keySet().stream().sorted().collect(Collectors.joining(",")) + ")");
            }
            return check.refresh(purchase);
        };
    }
}
