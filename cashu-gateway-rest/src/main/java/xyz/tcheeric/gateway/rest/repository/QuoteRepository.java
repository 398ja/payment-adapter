/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package xyz.tcheeric.gateway.rest.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;

/**
 *
 * @author eric
 */
@RepositoryRestResource(collectionResourceRel = "quotes", path = "quote")
public interface QuoteRepository extends PagingAndSortingRepository<GatewayQuote, Long>, CrudRepository<GatewayQuote,Long> {
    
    GatewayQuote findById(@Param("id") long id);
    
    GatewayQuote findByQuoteId(@Param("quoteId") String quoteId);

    GatewayQuote findByInvoiceId(@Param("invoiceId") String invoiceId);
}
