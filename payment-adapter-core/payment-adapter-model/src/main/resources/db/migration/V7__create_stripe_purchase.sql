-- A coupon purchase that was paid for and has not yet been delivered.
--
-- The durable half of "paid means owed": written transactionally with accepting
-- the Stripe webhook, worked off later by the issuer. Until then the customer
-- has paid and holds nothing, which is recoverable only because this row exists.
--
-- Named for the payment rather than the coupon. This records THAT a purchase was
-- paid and is undischarged, which is a payments fact; it holds no coupon, no
-- token, and no opinion about what discharging involves.
CREATE TABLE stripe_purchase (
    id UUID PRIMARY KEY,
    -- Stripe's event id. UNIQUE is the last line of defence against a
    -- redelivery becoming a second coupon for one payment.
    event_id VARCHAR(255) NOT NULL UNIQUE,
    checkout_session_id VARCHAR(255) NOT NULL,
    payment_intent_id VARCHAR(255),
    -- The stall that was paid, and that owes the coupon.
    connected_account_id VARCHAR(255) NOT NULL,
    -- Null when the buyer had no wallet and takes delivery by claim link.
    recipient_pubkey VARCHAR(64),
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    livemode BOOLEAN NOT NULL DEFAULT FALSE,
    -- OWED | DISCHARGED | BLOCKED. Never deleted to mean done: a discharged
    -- purchase is the evidence that a customer was served.
    status VARCHAR(32) NOT NULL,
    -- Set only on DISCHARGED, so a discharge names the coupon that discharged
    -- it. A discharged row with no voucher id is a detectable bug.
    voucher_id VARCHAR(255),
    last_failure VARCHAR(500),
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_stripe_purchase_session ON stripe_purchase (checkout_session_id);
-- The issuer's only query: what is still owed, oldest first.
CREATE INDEX idx_stripe_purchase_status ON stripe_purchase (status, created_at);
