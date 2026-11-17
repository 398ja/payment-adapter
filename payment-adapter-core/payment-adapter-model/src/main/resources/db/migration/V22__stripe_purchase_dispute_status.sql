-- payment-adapter#265 / imani-wallet#277: how a card purchase's dispute ended, so a won dispute can
-- restore the voucher and the wallet can say "Disputed" vs "Dispute won" vs "Dispute lost".
--   dispute_status: OPEN | WON | LOST, null when never disputed. `disputed` stays the blocking flag:
--   true while OPEN or after LOST, false again after WON.
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS dispute_status VARCHAR(8);
-- The stall's refund-state read (GET /api/v1/purchase-state/stall/{pubkey}) lists by stall.
CREATE INDEX IF NOT EXISTS idx_stripe_purchase_stall_pubkey ON stripe_purchase (stall_pubkey);
