package xyz.tcheeric.payment.adapter.core.model.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
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

    /**
     * What a worker may claim now, oldest first: every OWED row, and every
     * ISSUING row whose claim has lapsed because its worker died
     * (imani-wallet#96).
     *
     * <p>A lapsed claim must come back into view, or a crash mid-mint would
     * leave a paid purchase stranded forever. It comes back to be CLAIMED
     * again, never minted directly; the re-claim mints under the same
     * idempotency key.
     */
    @Query("select p from StripePurchase p where p.status = :owed"
            + " or (p.status = :issuing and p.claimedUntil < :now)"
            + " order by p.createdAt asc")
    List<StripePurchase> findClaimable(
            @Param("owed") StripePurchase.Status owed,
            @Param("issuing") StripePurchase.Status issuing,
            @Param("now") Instant now);

    /**
     * Claim one purchase for minting: OWED, or ISSUING with a lapsed lease,
     * becomes ISSUING until {@code until} (imani-wallet#96).
     *
     * <p>ONE conditional UPDATE, so the database decides who wins. Two workers
     * racing the same row both issue this statement and exactly one of them
     * gets a row count of 1. That count is the claim: 1 means mint, 0 means
     * somebody else has it or it is no longer owed.
     *
     * @return rows updated, 0 or 1
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StripePurchase p set p.status = :issuing, p.claimedUntil = :until,"
            + " p.claimedBy = :worker, p.updatedAt = :now"
            + " where p.eventId = :eventId"
            + " and (p.status = :owed or (p.status = :issuing and p.claimedUntil < :now))")
    int claim(
            @Param("eventId") String eventId,
            @Param("worker") String worker,
            @Param("now") Instant now,
            @Param("until") Instant until,
            @Param("owed") StripePurchase.Status owed,
            @Param("issuing") StripePurchase.Status issuing);
}
