package xyz.tcheeric.payment.adapter.core.model.repository;

import org.springframework.data.repository.CrudRepository;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StallInstalmentSettings;

/** Keyed by the stall's lower-case hex key. */
public interface StallInstalmentSettingsRepository extends CrudRepository<StallInstalmentSettings, String> {}
