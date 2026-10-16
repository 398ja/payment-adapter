-- payment-adapter#272 (card atomicity audit U10, S10): reconcile stripe_purchase against Stripe.
--
-- Webhooks were the only way a paid card purchase reached stripe_purchase, so a DB restore, a
-- webhook outage longer than Stripe's ~3-day retry, or a bug that ACKs without recording lost the
-- debt for good. StripePurchaseReconciler lists each charges_enabled connected account's complete
-- Checkout Sessions and records the paid ones that have no row (synthetic event id
-- reconcile:<cs_id>; the V14 uniques dedupe a racing webhook).
--
-- One row per connected account:
--   created_through  every session created before this (less the overlap) has been reconciled
--   pass_until       upper bound of the pass in progress. Stripe lists newest first, so a pass is
--                    a fixed window [created_through - overlap, pass_until] walked page by page
--   resume_after     last session handled in that pass, so a bounded run continues where the
--                    previous one stopped instead of re-reading the newest page forever
--   lease_until/by   one replica per account at a time. PA has no ShedLock; this is the same
--                    conditional-UPDATE lease the purchase claim uses
CREATE TABLE IF NOT EXISTS stripe_reconcile_cursor (
    account_id      VARCHAR(255) PRIMARY KEY,
    created_through TIMESTAMP WITH TIME ZONE NOT NULL,
    pass_until      TIMESTAMP WITH TIME ZONE,
    resume_after    VARCHAR(255),
    lease_until     TIMESTAMP WITH TIME ZONE,
    leased_by       VARCHAR(128),
    last_run_at     TIMESTAMP WITH TIME ZONE,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
