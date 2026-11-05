-- imani-wallet#410 code gifts: what the gift is (null = a voucher, as every draft before), and for a
-- code gift the shop it is spent at, pinned at review from the stall's signed online_shop (contract §4).
ALTER TABLE purchase_draft ADD COLUMN IF NOT EXISTS gift_kind VARCHAR(16);
ALTER TABLE purchase_draft ADD COLUMN IF NOT EXISTS shop_key VARCHAR(64);
ALTER TABLE purchase_draft ADD COLUMN IF NOT EXISTS shop_url VARCHAR(2048);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS gift_kind VARCHAR(16);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS shop_key VARCHAR(64);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS shop_url VARCHAR(2048);
-- x, epoch seconds: pinned on the draft so the date the buyer is told is the one the issuer locks.
ALTER TABLE purchase_draft ADD COLUMN IF NOT EXISTS code_expires_at BIGINT;
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS code_expires_at BIGINT;
