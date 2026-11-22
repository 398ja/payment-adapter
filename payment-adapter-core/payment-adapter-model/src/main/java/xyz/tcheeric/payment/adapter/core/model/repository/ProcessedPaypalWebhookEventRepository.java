package xyz.tcheeric.payment.adapter.core.model.repository;

import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ProcessedPaypalWebhookEvent;

public interface ProcessedPaypalWebhookEventRepository extends CrudRepository<ProcessedPaypalWebhookEvent, String> {
}
