-- Business metrics (imani_product_*{product="gift"}): what happened to a delivered gift, as the
-- purchase issuer's gift tracker saw it. Reported here so ONE owner counts every gift state and
-- nothing is counted twice. Closed values, no personal data:
--   gift_outcome: PARTLY_USED | CLAIMED | EXPIRED | RETURNED, null while nothing is known yet.
--   gift_remaining_minor: a code gift's unspent balance as last reported; null = the full amount.
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS gift_outcome VARCHAR(16);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS gift_remaining_minor BIGINT;
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS gift_outcome_at TIMESTAMP WITH TIME ZONE;
-- The gift gauge reads only gift rows (delivery handle or email), grouped: no full scan per poll.
CREATE INDEX IF NOT EXISTS idx_stripe_purchase_delivery_kind ON stripe_purchase (delivery_kind);
-- The issuer reports by draft id, which is all its tracker holds.
CREATE INDEX IF NOT EXISTS idx_stripe_purchase_draft_id ON stripe_purchase (draft_id);
