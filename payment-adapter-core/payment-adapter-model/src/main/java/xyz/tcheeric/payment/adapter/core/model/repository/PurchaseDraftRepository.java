package xyz.tcheeric.payment.adapter.core.model.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.model.entity.gifting.PurchaseDraft;

/** imani-wallet#410: gifting Purchase Drafts. */
public interface PurchaseDraftRepository extends CrudRepository<PurchaseDraft, UUID> {

    /**
     * imani-wallet#570: drop a draft's personal fields (buyer and recipient email, message, sender
     * name, sealed secret). Amount, stall, keys and timestamps stay for accounting.
     *
     * @return rows changed, 0 or 1
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PurchaseDraft d set d.buyerEmail = null, d.recipientEmail = null, d.message = null,"
            + " d.senderName = null, d.sealedSecret = null, d.version = d.version + 1"
            + " where d.id = :id and (d.buyerEmail is not null or d.recipientEmail is not null"
            + " or d.message is not null or d.senderName is not null or d.sealedSecret is not null)")
    int scrubPersonalFields(@Param("id") UUID id);

    /**
     * imani-wallet#570: drafts never paid for and past {@code expiresAt} before {@code cutoff} lose
     * their personal fields. Nothing will ever be delivered from them.
     *
     * @return rows changed
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PurchaseDraft d set d.buyerEmail = null, d.recipientEmail = null, d.message = null,"
            + " d.senderName = null, d.sealedSecret = null, d.version = d.version + 1"
            + " where d.consumedAt is null and d.expiresAt < :cutoff"
            + " and (d.buyerEmail is not null or d.recipientEmail is not null"
            + " or d.message is not null or d.senderName is not null or d.sealedSecret is not null)")
    int scrubAbandoned(@Param("cutoff") Instant cutoff);

    /**
     * imani-wallet#570 backfill: a paid draft whose purchase has already lost its personal fields
     * (an earlier {@code scrub?all=true}, or the backstop) loses its own copy too.
     *
     * @return rows changed
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PurchaseDraft d set d.buyerEmail = null, d.recipientEmail = null, d.message = null,"
            + " d.senderName = null, d.sealedSecret = null, d.version = d.version + 1"
            + " where d.consumedAt is not null"
            + " and (d.buyerEmail is not null or d.recipientEmail is not null"
            + " or d.message is not null or d.senderName is not null or d.sealedSecret is not null)"
            + " and exists (select p.id from StripePurchase p where p.draftId = d.id and p.scrubbedAt is not null"
            + " and p.buyerEmail is null and p.recipientEmail is null and p.giftMessage is null"
            + " and p.senderName is null)")
    int scrubWherePurchaseScrubbed();
}
