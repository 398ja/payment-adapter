-- payment-adapter#267 (policy on #265): a card purchase can be refunded or disputed.
--
-- charge.refunded / charge.dispute.created on a purchase answered 500 (no purchase
-- branch), and nothing stopped a refunded payment being minted (card atomicity audit
-- S8, S12). The handler now records the reversal on the purchase row:
--   * refunded before a coupon exists -> status REFUNDED (VARCHAR, no change needed);
--     the claim never matches it, so it is never minted.
--   * refunded or disputed after issue -> facts only (backed = amount - refunded is
--     derivable). Refunds of issued coupons are not supported yet (#265).
--   * disputed -> disputed = true, recorded only.
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS refunded_amount_minor BIGINT;
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS disputed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS reversed_at TIMESTAMP WITH TIME ZONE;
-- Optimistic lock (review of #278): the refund handler and a discharge both save the whole row,
-- and a discharge holds its copy while it asks the gateway. Existing rows start at 0.
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
