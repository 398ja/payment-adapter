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
import xyz.tcheeric.gateway.model.entity.GatewayPayment;

/**
 *
 * @author eric
 */
@RepositoryRestResource(collectionResourceRel = "payments", path = "payment")
public interface PaymentRepository extends PagingAndSortingRepository<GatewayPayment, Long> {

    // Inherit save(...) from CrudRepository; do not redeclare to avoid compiler issues in some toolchains

    /**
     * Retrieves a payment by its external payment identifier.
     * The query selects a payment matching the provided {@code paymentId}.
     * Returns an {@link Optional} that is empty when no payment exists.
     *
     * @param paymentId the external payment identifier
     * @return an optional payment for the given identifier
     */
    @Query("select p from payment p where p.paymentId = :paymentId")
    Optional<GatewayPayment> findByPaymentId(@Param("paymentId") String paymentId);

    /**
     * Retrieves a payment by its associated quote identifier.
     * The query selects a payment matching the given {@code quoteId}.
     * Returns an {@link Optional} that is empty when no payment matches.
     *
     * @param quoteId the associated quote identifier
     * @return an optional payment for the given quote identifier
     */
    @Query("select p from payment p where p.quoteId = :quoteId")
    Optional<GatewayPayment> findByQuoteId(@Param("quoteId") String quoteId);
}
