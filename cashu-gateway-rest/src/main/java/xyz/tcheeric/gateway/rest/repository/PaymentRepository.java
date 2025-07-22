/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package xyz.tcheeric.gateway.rest.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;

/**
 *
 * @author eric
 */
@RepositoryRestResource(collectionResourceRel = "payments", path = "payment")
public interface PaymentRepository extends PagingAndSortingRepository<GatewayPayment, Long>, CrudRepository<GatewayPayment,Long> {

    @Query("select p from payment p where p.paymentId = :paymentId")
    GatewayPayment findByPaymentId(@Param("paymentId") String paymentId);

    @Query("select p from payment p where p.quoteId = :quoteId")
    GatewayPayment findByQuoteId(@Param("quoteId") String quoteId);
}