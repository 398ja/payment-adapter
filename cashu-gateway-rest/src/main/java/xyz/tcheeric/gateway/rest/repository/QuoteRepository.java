/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package xyz.tcheeric.gateway.rest.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;

/**
 *
 * @author eric
 */
@RepositoryRestResource(collectionResourceRel = "quotes", path = "quote")
public interface QuoteRepository extends PagingAndSortingRepository<GatewayQuote, Long> {

    // Explicitly declare save to satisfy RepositoryInvoker detection in some setups
    <S extends GatewayQuote> S save(S entity);

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
}
