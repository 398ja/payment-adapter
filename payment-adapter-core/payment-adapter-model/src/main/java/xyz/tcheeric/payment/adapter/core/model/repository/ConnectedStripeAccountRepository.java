package xyz.tcheeric.payment.adapter.core.model.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;

public interface ConnectedStripeAccountRepository extends CrudRepository<ConnectedStripeAccount, UUID> {

    Optional<ConnectedStripeAccount> findByMerchantPubkey(String merchantPubkey);

    Optional<ConnectedStripeAccount> findByStripeAccountId(String stripeAccountId);

    void deleteByMerchantPubkey(String merchantPubkey);
}
