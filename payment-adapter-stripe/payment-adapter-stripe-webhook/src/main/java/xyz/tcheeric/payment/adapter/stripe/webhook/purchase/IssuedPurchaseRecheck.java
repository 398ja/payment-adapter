package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer behind {@link PurchaseDischargeService#recheckIssued}
 * (payment-adapter#260 review, PA-2).
 *
 * <p>Without it, a discharge the gateway could not verify was never asked
 * about again and the row sat in ISSUED until a person found it.
 */
@Slf4j
@RequiredArgsConstructor
public class IssuedPurchaseRecheck {

    private final PurchaseDischargeService discharges;
    private final GatewayFulfilmentClient fulfilment;

    @Scheduled(fixedDelayString = "${purchase.recheck.interval-ms:300000}",
            initialDelayString = "${purchase.recheck.initial-delay-ms:60000}")
    public void run() {
        if (!fulfilment.isEnabled()) {
            return;
        }
        try {
            discharges.recheckIssued(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Issued purchase re-check failed: {}", e.getMessage());
        }
    }
}
