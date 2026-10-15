-- payment-adapter#273 (card atomicity audit U7): NEEDS_ATTENTION.
--
-- Some purchases cannot be moved on by anything automatic: gc answered 409 UNRECORDED
-- for the delegated-warrant nonce (the mint state is unknown, so a retry could mint
-- twice), the issuer's delivery gave up, or a claim lease lapsed again and again.
-- Those rows now park in status NEEDS_ATTENTION (VARCHAR, no change needed) with a
-- reason, are never claimed, and move on only through an operator's /resolve.
--
-- lease_expiries counts takeovers of a lapsed claim, so a worker that keeps dying
-- mid-mint parks the row (stale_issuing) rather than minting on a loop.
--
-- Refunds and disputes after issue stay informational (#265: not supported, no
-- revocation). reversal_acknowledged_at lets an operator acknowledge them without
-- any status change; a later reversal raises them again.
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS attention_reason VARCHAR(32);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS attention_detail VARCHAR(500);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS attention_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS lease_expiries INTEGER NOT NULL DEFAULT 0;
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS resolved_by VARCHAR(128);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS resolution_note VARCHAR(500);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS reversal_acknowledged_at TIMESTAMP WITH TIME ZONE;
