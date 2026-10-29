-- imani-wallet#410: the Purchase Draft behind gifting.imani.casa.
--
-- A draft is what the buyer reviewed, re-checked and PINNED server-side before any Checkout
-- Session exists (G-R1): the delivery target (a resolved recipient pubkey, or an email address),
-- the refund key R, and the sealed link secret (G4). Stripe metadata carries only draft_id, so
-- no personal data reaches Stripe and nothing after review can change who receives the voucher.
--
-- sealed_secret, recipient_email and message are personal or secret: scrubbed once delivered
-- (POST /api/v1/purchases/{eventId}/scrub) and never logged.
CREATE TABLE IF NOT EXISTS purchase_draft (
    id                  UUID PRIMARY KEY,
    stall_pubkey        VARCHAR(64)  NOT NULL,
    stall_handle        VARCHAR(320),
    amount_minor        BIGINT       NOT NULL,
    currency            VARCHAR(8)   NOT NULL,
    terms_version       VARCHAR(128),
    delivery_kind       VARCHAR(16)  NOT NULL,
    recipient_pubkey    VARCHAR(64),
    recipient_email     VARCHAR(320),
    refund_pubkey       VARCHAR(64),
    sealed_secret       VARCHAR(2048),
    message             VARCHAR(280),
    sender_name         VARCHAR(80),
    buyer_pubkey        VARCHAR(64),
    buyer_email         VARCHAR(320),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    invalidated_at      TIMESTAMP WITH TIME ZONE,
    checkout_session_id VARCHAR(255) UNIQUE,
    checkout_url        VARCHAR(2048),
    consumed_at         TIMESTAMP WITH TIME ZONE,
    version             BIGINT       NOT NULL DEFAULT 0
);

-- The pinned delivery, copied onto the debt when the payment is recorded.
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS draft_id UUID;
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS delivery_kind VARCHAR(16);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS refund_pubkey VARCHAR(64);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS recipient_email VARCHAR(320);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS sealed_secret VARCHAR(2048);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS gift_message VARCHAR(280);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS sender_name VARCHAR(80);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS buyer_email VARCHAR(320);
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS scrubbed_at TIMESTAMP WITH TIME ZONE;
