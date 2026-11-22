package xyz.tcheeric.payment.adapter.core.model.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.PaypalCheckout;

public interface PaypalCheckoutRepository extends CrudRepository<PaypalCheckout, UUID> {

    Optional<PaypalCheckout> findByOrderId(String orderId);

    Optional<PaypalCheckout> findByQuoteId(String quoteId);

    Optional<PaypalCheckout> findByCaptureId(String captureId);

    /** The reconciler's worklist: orders still CREATED in a window, oldest first. */
    List<PaypalCheckout> findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
            PaypalCheckout.Status status, Instant from, Instant until, Pageable page);
}
