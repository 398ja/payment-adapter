package xyz.tcheeric.payment.adapter.core.model.repository;

import java.util.UUID;
import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.gifting.PurchaseDraft;

/** imani-wallet#410: gifting Purchase Drafts. */
public interface PurchaseDraftRepository extends CrudRepository<PurchaseDraft, UUID> {
}
