-- PayPal card purchases (docs/adr/0002-paypal-card-purchases-mirror-stripe.md).
--
-- One ledger for both processors. stripe_purchase records that a card purchase was paid and is
-- undischarged, which is not a Stripe fact, so PayPal rows live there too and the Purchase Issuer,
-- discharge verification, attention flows and gauges serve them unchanged. `processor` says whose
-- payment it is; every existing row is Stripe's. For a PayPal row:
--   checkout_session_id   the PayPal order id
--   payment_intent_id     the PayPal capture id (the processor reference a coupon's warrant carries)
--   connected_account_id  the stall's PayPal merchant id
ALTER TABLE stripe_purchase ADD COLUMN IF NOT EXISTS processor VARCHAR(16) NOT NULL DEFAULT 'stripe';
CREATE INDEX IF NOT EXISTS idx_stripe_purchase_processor ON stripe_purchase (processor);

-- The Connect account's analogue: a seller onboarded through Partner Referrals. The merchant id is
-- unknown until the seller finishes PayPal's flow, so it is nullable and found by tracking_id
-- (the stall's pubkey) until then.
CREATE TABLE IF NOT EXISTS connected_paypal_account (
    id                      UUID PRIMARY KEY,
    merchant_pubkey         VARCHAR(255) NOT NULL UNIQUE,
    tracking_id             VARCHAR(127) NOT NULL UNIQUE,
    paypal_merchant_id      VARCHAR(64) UNIQUE,
    onboarding_complete     BOOLEAN NOT NULL DEFAULT FALSE,
    charges_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    payouts_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    details_submitted       BOOLEAN NOT NULL DEFAULT FALSE,
    primary_email_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    default_currency        VARCHAR(8),
    requirements_due        VARCHAR(4096),
    disabled_reason         VARCHAR(255),
    country                 VARCHAR(8),
    email                   VARCHAR(255),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
);

-- What a Checkout Session's metadata carries for Stripe. PayPal orders have only a 127-character
-- custom_id, so the purchase's terms are written here before the buyer sees PayPal, and read back
-- when the capture lands. Also the reconciler's worklist: PayPal cannot list a seller's orders.
CREATE TABLE IF NOT EXISTS paypal_checkout (
    id                 UUID PRIMARY KEY,
    order_id           VARCHAR(64) NOT NULL UNIQUE,
    quote_id           VARCHAR(64) NOT NULL UNIQUE,
    stall_pubkey       VARCHAR(64) NOT NULL,
    paypal_merchant_id VARCHAR(64) NOT NULL,
    amount_minor       BIGINT NOT NULL,
    currency           VARCHAR(8) NOT NULL,
    metadata           VARCHAR(4096),
    environment        VARCHAR(64),
    livemode           BOOLEAN NOT NULL DEFAULT FALSE,
    status             VARCHAR(16) NOT NULL,
    capture_id         VARCHAR(64) UNIQUE,
    last_error         VARCHAR(500),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_paypal_checkout_status_created ON paypal_checkout (status, created_at);

-- Same lifecycle as processed_stripe_webhook_event (V4, V15): PROCESSING is taken before the work,
-- a stale one is taken over, PROCESSED answers redeliveries.
CREATE TABLE IF NOT EXISTS processed_paypal_webhook_event (
    event_id              VARCHAR(255) PRIMARY KEY,
    event_type            VARCHAR(255) NOT NULL,
    payload_hash          VARCHAR(255) NOT NULL,
    livemode              BOOLEAN NOT NULL DEFAULT FALSE,
    received_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    processing_status     VARCHAR(32) NOT NULL,
    processing_started_at TIMESTAMP WITH TIME ZONE,
    processed_at          TIMESTAMP WITH TIME ZONE,
    last_error            VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS idx_processed_paypal_event_processed_at ON processed_paypal_webhook_event (processed_at);
