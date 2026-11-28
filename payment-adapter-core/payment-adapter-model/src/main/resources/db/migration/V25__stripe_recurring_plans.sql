-- Instalment plans (docs/specs/payment-adapter-stripe-recurring-spec.md).
--
-- A plan is a Stripe subscription on the STALL's own connected account (a direct charge): the stall is merchant
-- of record and the funds settle in its balance. Imani holds the plan so each paid instalment can issue its own
-- vouchers to the buyer through the existing "paid means owed" ledger (stripe_purchase).
--
-- The buyer and stall are recorded here, server-side, BEFORE the buyer reaches Stripe. Nothing about who receives
-- the vouchers is taken from metadata on a later event.
CREATE TABLE IF NOT EXISTS recurring_plan (
    id                   UUID PRIMARY KEY,
    connected_account_id VARCHAR(255) NOT NULL,
    stall_pubkey         VARCHAR(64)  NOT NULL,
    buyer_pubkey         VARCHAR(64)  NOT NULL,
    amount_minor         BIGINT       NOT NULL,
    currency             VARCHAR(8)   NOT NULL,
    interval_unit        VARCHAR(8)   NOT NULL DEFAULT 'month',
    -- Null = open-ended. Otherwise Imani cancels the subscription at period end once this many instalments
    -- are paid, counting paid ledger rows (not a counter that a redelivery could advance twice).
    total_instalments    INTEGER,
    -- PENDING | ACTIVE | PAST_DUE | COMPLETED | CANCELLED | ORPHANED | ATTENTION
    status               VARCHAR(16)  NOT NULL,
    checkout_session_id  VARCHAR(255),
    subscription_id      VARCHAR(255) UNIQUE,
    livemode             BOOLEAN      NOT NULL DEFAULT FALSE,
    cancel_requested_at  TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_recurring_plan_account_status ON recurring_plan (connected_account_id, status);
CREATE INDEX IF NOT EXISTS idx_recurring_plan_buyer ON recurring_plan (buyer_pubkey);

-- Which plan an instalment's purchase row belongs to. For an instalment, checkout_session_id holds the INVOICE
-- id (in_...) and payment_intent_id the invoice's payment intent, the same reuse of the Stripe-named columns that
-- ADR 0002 makes for PayPal. Both stay unique, which makes "one row per paid invoice" a database guarantee.
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS recurring_plan_id UUID;
CREATE INDEX IF NOT EXISTS idx_stripe_purchase_recurring_plan ON stripe_purchase (recurring_plan_id);
