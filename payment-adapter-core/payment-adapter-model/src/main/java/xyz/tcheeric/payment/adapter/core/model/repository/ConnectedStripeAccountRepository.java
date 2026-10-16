package xyz.tcheeric.payment.adapter.core.model.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;

public interface ConnectedStripeAccountRepository extends CrudRepository<ConnectedStripeAccount, UUID> {

    Optional<ConnectedStripeAccount> findByMerchantPubkey(String merchantPubkey);

    Optional<ConnectedStripeAccount> findByStripeAccountId(String stripeAccountId);

    /** Accounts that can take a card payment: the only ones the reconciler reads (#272). */
    java.util.List<ConnectedStripeAccount> findByChargesEnabledTrue();

    void deleteByMerchantPubkey(String merchantPubkey);
}
