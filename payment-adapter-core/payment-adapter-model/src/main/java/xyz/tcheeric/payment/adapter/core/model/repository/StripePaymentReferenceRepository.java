package xyz.tcheeric.payment.adapter.core.model.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePaymentReference;

public interface StripePaymentReferenceRepository extends CrudRepository<StripePaymentReference, UUID> {

    Optional<StripePaymentReference> findByQuoteId(String quoteId);

    Optional<StripePaymentReference> findByCheckoutSessionId(String checkoutSessionId);

    Optional<StripePaymentReference> findByPaymentIntentId(String paymentIntentId);

    Optional<StripePaymentReference> findByChargeId(String chargeId);
}
