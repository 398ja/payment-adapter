package xyz.tcheeric.payment.adapter.core.model.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;

public interface ConnectedPaypalAccountRepository extends CrudRepository<ConnectedPaypalAccount, UUID> {

    Optional<ConnectedPaypalAccount> findByMerchantPubkey(String merchantPubkey);

    Optional<ConnectedPaypalAccount> findByPaypalMerchantId(String paypalMerchantId);

    Optional<ConnectedPaypalAccount> findByTrackingId(String trackingId);

    List<ConnectedPaypalAccount> findByChargesEnabledTrue();

    void deleteByMerchantPubkey(String merchantPubkey);
}
