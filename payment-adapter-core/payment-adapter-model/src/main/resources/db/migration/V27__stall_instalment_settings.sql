-- A stall's own choice to offer instalment plans, and how often a buyer may pay.
--
-- The stall's switch used to live only in its public Nostr profile (`acceptsInstalments`), which the wallet reads to
-- show "Pay monthly" but which nothing here could enforce: a direct call to POST /api/v1/checkout/plan started a plan
-- on any eligible stall. This row is written only by a request signed BY the stall's key, and plan start refuses a
-- stall without one that is enabled and lists the requested interval. No row means "not offered".
CREATE TABLE IF NOT EXISTS stall_instalment_settings (
    stall_pubkey VARCHAR(64)  PRIMARY KEY,
    enabled      BOOLEAN      NOT NULL,
    -- Comma-separated Stripe interval units the stall accepts: week, month, year.
    intervals    VARCHAR(64)  NOT NULL DEFAULT 'month',
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
