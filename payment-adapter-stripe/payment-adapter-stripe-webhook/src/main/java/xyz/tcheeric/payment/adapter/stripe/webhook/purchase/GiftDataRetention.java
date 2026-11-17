package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.PurchaseDraftRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.GiftingService;

/**
 * imani-wallet#570: personal data on gift purchases and drafts does not outlive its purpose.
 *
 * <p>The purchase issuer's gift tracker scrubs a purchase ({@code scrub?all=true}) as soon as it is
 * done with a gift. This job covers what the tracker never sees, and is the backstop if it never
 * calls:
 * <ul>
 *   <li><b>Abandoned drafts</b>: never paid for and past {@code expires_at} by
 *       {@code purchase.retention.abandoned-draft-days} (default 30): personal fields nulled.</li>
 *   <li><b>Backstop</b>: a gift purchase still holding personal fields
 *       {@code purchase.retention.backstop-days} (default 400) after its claim window (vouchers) or
 *       code expiry (codes) is scrubbed, with its draft, and logged as a warning (event id only).</li>
 *   <li><b>Backfill</b>: a paid draft whose purchase was already scrubbed loses its copy too. Every
 *       run does it, so the first run after deploy is the one-off backfill of existing rows.</li>
 * </ul>
 *
 * <p>Amount, currency, stall, timestamps and Stripe ids stay (accounting). Never logs an address.
 */
@Slf4j
public class GiftDataRetention {

    private static final int BACKSTOP_PAGE = 200;

    private final StripePurchaseRepository purchases;
    private final PurchaseDraftRepository drafts;
    private final Duration abandonedDraftAge;
    private final Duration backstopAge;
    private final Clock clock;

    public GiftDataRetention(StripePurchaseRepository purchases, PurchaseDraftRepository drafts,
            Duration abandonedDraftAge, Duration backstopAge, Clock clock) {
        this.purchases = purchases;
        this.drafts = drafts;
        this.abandonedDraftAge = abandonedDraftAge;
        this.backstopAge = backstopAge;
        this.clock = clock;
    }

    /** What one run changed, for the log and the tests. */
    public record Result(int abandonedDrafts, int backstopPurchases, int backfilledDrafts) {
    }

    /** Null every personal field of a gift purchase. The one list both scrub paths use. */
    static void clearPersonalFields(StripePurchase row) {
        row.setSealedSecret(null);
        row.setRecipientEmail(null);
        row.setGiftMessage(null);
        row.setBuyerEmail(null);
        row.setSenderName(null);
    }

    @Scheduled(fixedDelayString = "${purchase.retention.interval-ms:3600000}",
            initialDelayString = "${purchase.retention.initial-delay-ms:120000}")
    public void scheduled() {
        try {
            Result r = run();
            if (r.abandonedDrafts() + r.backstopPurchases() + r.backfilledDrafts() > 0) {
                log.info("Gift data retention: {} abandoned drafts, {} purchases past the backstop, {} drafts backfilled",
                        r.abandonedDrafts(), r.backstopPurchases(), r.backfilledDrafts());
            }
        } catch (RuntimeException e) {
            log.warn("Gift data retention failed: {}", e.getClass().getSimpleName());
        }
    }

    public Result run() {
        Instant now = clock.instant();
        int abandoned = drafts == null ? 0 : drafts.scrubAbandoned(now.minus(abandonedDraftAge));
        int backstop = scrubPastBackstop(now);
        int backfilled = drafts == null ? 0 : drafts.scrubWherePurchaseScrubbed();
        return new Result(abandoned, backstop, backfilled);
    }

    private int scrubPastBackstop(Instant now) {
        Instant voucherBefore = now.minus(backstopAge).minus(Duration.ofDays(GiftingService.CLAIM_WINDOW_DAYS));
        long codeBefore = now.minus(backstopAge).getEpochSecond();
        List<StripePurchase> due = purchases.findPersonalDataPastBackstop(voucherBefore, codeBefore,
                PageRequest.of(0, BACKSTOP_PAGE));
        int done = 0;
        for (StripePurchase row : due) {
            if (scrubOne(row, now)) {
                done++;
            }
        }
        return done;
    }

    private boolean scrubOne(StripePurchase row, Instant now) {
        clearPersonalFields(row);
        row.setScrubbedAt(now);
        row.setUpdatedAt(now);
        try {
            purchases.save(row);
        } catch (OptimisticLockingFailureException e) {
            return false;
        }
        if (drafts != null && row.getDraftId() != null) {
            drafts.scrubPersonalFields(row.getDraftId());
        }
        // The tracker should have done this long ago: loud, with the event id only.
        log.warn("Gift purchase {} still held personal data past the retention backstop; scrubbed", row.getEventId());
        return true;
    }
}
