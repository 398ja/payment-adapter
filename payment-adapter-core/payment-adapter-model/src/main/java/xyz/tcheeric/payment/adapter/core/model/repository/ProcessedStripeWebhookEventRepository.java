package xyz.tcheeric.payment.adapter.core.model.repository;

import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ProcessedStripeWebhookEvent;

public interface ProcessedStripeWebhookEventRepository extends CrudRepository<ProcessedStripeWebhookEvent, String> {
}
