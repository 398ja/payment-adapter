package xyz.tcheeric.payment.adapter.core.model.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;

/**
 * Reading and writing the debts a card purchase creates.
 *
 * <p>Two readers with different needs. The webhook writes one row and never
 * looks back. The issuer asks "what is owed", works through the answer, and
 * reports each outcome.
 */
public interface StripePurchaseRepository extends CrudRepository<StripePurchase, UUID> {

    /**
     * By Stripe event id, which is how a redelivery is recognised.
     *
     * <p>The unique constraint would catch a duplicate anyway, but as an
     * exception rather than an answer. Asking first turns "Stripe sent it
     * twice" into an ordinary outcome.
     */
    Optional<StripePurchase> findByEventId(String eventId);

    /**
     * Everything still owed, oldest first.
     *
     * <p>Oldest first because the longest-waiting customer is the one most
     * likely to give up, and because a purchase repeatedly failing must not
     * starve the ones behind it by always being retried last.
     */
    List<StripePurchase> findByStatusOrderByCreatedAtAsc(StripePurchase.Status status);
}
