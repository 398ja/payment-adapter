CREATE TABLE stripe_payment_reference (
    id UUID PRIMARY KEY,
    quote_id VARCHAR(255) NOT NULL UNIQUE,
    checkout_session_id VARCHAR(255) NOT NULL UNIQUE,
    payment_intent_id VARCHAR(255) UNIQUE,
    charge_id VARCHAR(255) UNIQUE,
    connected_account_id VARCHAR(255),
    stripe_status VARCHAR(64),
    livemode BOOLEAN NOT NULL DEFAULT FALSE,
    last_event_id VARCHAR(255),
    refunded_amount_minor INTEGER,
    disputed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_stripe_reference_quote_id ON stripe_payment_reference (quote_id);
CREATE INDEX idx_stripe_reference_checkout_session_id ON stripe_payment_reference (checkout_session_id);

CREATE TABLE processed_stripe_webhook_event (
    event_id VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload_hash VARCHAR(255) NOT NULL,
    livemode BOOLEAN NOT NULL DEFAULT FALSE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processing_status VARCHAR(32) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(1024)
);

CREATE INDEX idx_processed_stripe_event_processed_at ON processed_stripe_webhook_event (processed_at);

CREATE TABLE connected_stripe_account (
    id UUID PRIMARY KEY,
    merchant_pubkey VARCHAR(255) NOT NULL UNIQUE,
    stripe_account_id VARCHAR(255) NOT NULL UNIQUE,
    onboarding_complete BOOLEAN NOT NULL DEFAULT FALSE,
    charges_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    payouts_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_currency VARCHAR(8),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_connected_stripe_account_merchant_pubkey ON connected_stripe_account (merchant_pubkey);
