/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package xyz.tcheeric.payment.adapter.core.rest.repository;

import java.util.Optional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RestResource;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;

/**
 *
 * @author eric
 */
@RepositoryRestResource(collectionResourceRel = "quotes", path = "quote")
public interface QuoteRepository extends PagingAndSortingRepository<GatewayQuote, Long> {

    // Explicitly declare save to satisfy RepositoryInvoker detection in some setups
    <S extends GatewayQuote> S save(S entity);

    // Explicitly declare findById to enable PATCH/PUT/DELETE on item endpoints
    Optional<GatewayQuote> findById(Long id);

    /**
     * Retrieves a quote by its external quote identifier.
     * The query selects a quote matching the given {@code quoteId}.
     * Returns an {@link Optional} that is empty when no quote is found.
     *
     * @param quoteId the external quote identifier
     * @return an optional quote for the given identifier
     */
    @Query("select q from quote q where q.quoteId = :quoteId")
    Optional<GatewayQuote> findByQuoteId(@Param("quoteId") String quoteId);

    /**
     * Retrieves a quote by its Lightning invoice identifier.
     * The query selects a quote matching the given {@code invoiceId}.
     * Returns an {@link Optional} that is empty when no quote matches.
     *
     * @param invoiceId the Lightning invoice identifier
     * @return an optional quote for the given invoice identifier
     */
    @Query("select q from quote q where q.invoiceId = :invoiceId")
    Optional<GatewayQuote> findByInvoiceId(@Param("invoiceId") String invoiceId);

    /**
     * Settled payments the mint was never told about (398ja/cashu-mint#462).
     *
     * <p>A {@code PAID} quote with no {@code mintNotifiedAt} means the money arrived and the
     * forward did not. That used to be invisible — the forwarder's return value was discarded
     * and nothing recorded the attempt — so seven of these accumulated on staging, the oldest
     * for three weeks.
     *
     * <p>{@code confirmedBefore} is a grace period, not an age filter. The forward happens
     * inline on the webhook, so a quote paid seconds ago is mid-flight rather than stranded;
     * without the bound the sweep would race the very code path it exists to back up. It also
     * keeps rows predating {@code mint_notified_at} out of reach on first deploy, since their
     * forwarding state is unknown rather than known-bad.
     *
     * <p>{@code @RestResource(exported = false)}: this repository is published over Spring Data
     * REST, and a query listing every payment the mint has not acknowledged is an inventory of
     * where value is currently unaccounted for. It is for the reconciler, not for callers.
     */
    @RestResource(exported = false)
    @Query("select q from quote q where q.state = xyz.tcheeric.payment.adapter.core.model.entity.enums.State.PAID "
            + "and q.mintNotifiedAt is null and q.createdAt < :confirmedBefore order by q.createdAt")
    List<GatewayQuote> findPaidButNotForwarded(@Param("confirmedBefore") Instant confirmedBefore,
                                               Limit limit);

    /**
     * Issue 398ja/cashu-mint#462 — the Paid-Unforwarded invariant, exported as
     * {@code payment_adapter_paid_unforwarded}.
     *
     * <p>Non-zero means money was taken and the mint does not know, so it can neither issue
     * against it nor see that it is missing. <strong>This gauge has to live on this side of the
     * boundary</strong>: cashu-mint's own paid-unfunded gauge counts quotes that have an accepted
     * webhook event, so a payment that never arrived is invisible to it. Only the adapter knows
     * the payment happened at all.
     *
     * <p>Unbounded by time, unlike the sweep: the gauge counts the standing liability, while the
     * grace period belongs to the thing taking action.
     */
    @RestResource(exported = false)
    @Query("select count(q) from quote q where q.state = xyz.tcheeric.payment.adapter.core.model.entity.enums.State.PAID "
            + "and q.mintNotifiedAt is null")
    long countPaidButNotForwarded();
}
