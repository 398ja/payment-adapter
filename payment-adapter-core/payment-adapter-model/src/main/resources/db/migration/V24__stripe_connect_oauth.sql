-- Connect an existing Stripe account through OAuth (docs/specs/payment-adapter-stripe-recurring-spec.md, R6-R9).
--
-- connected_stripe_account gains how the account was connected and the responsibilities Stripe reports for
-- it. They are recorded once, when the account is connected: Stripe fixes the fee payer and dashboard type at
-- creation, so a webhook refresh never changes them. All four are nullable because an account the stall opened
-- itself may not report a `controller` block.
--   connection_type  CREATED (Imani created it, then sent the stall through onboarding) | OAUTH (the stall's own)
--   account_type     Stripe's `type` (standard, express, custom, none)
--   fee_payer        controller.fees.payer (account | application | application_express | application_custom)
--   losses_payer     controller.losses.payments (stripe | application)
--   dashboard_type   controller.stripe_dashboard.type (full | express | none)
ALTER TABLE connected_stripe_account ADD COLUMN IF NOT EXISTS connection_type VARCHAR(16) NOT NULL DEFAULT 'CREATED';
ALTER TABLE connected_stripe_account ADD COLUMN IF NOT EXISTS account_type VARCHAR(16);
ALTER TABLE connected_stripe_account ADD COLUMN IF NOT EXISTS fee_payer VARCHAR(32);
ALTER TABLE connected_stripe_account ADD COLUMN IF NOT EXISTS losses_payer VARCHAR(16);
ALTER TABLE connected_stripe_account ADD COLUMN IF NOT EXISTS dashboard_type VARCHAR(16);

-- One row per OAuth round trip. `state` is the opaque value Stripe hands back to the redirect URI. It is the
-- only thing that ties the farm's browser to the merchant who started the flow, so it is random, short-lived and
-- single use. It is consumed (consumed_at set) in its own committed transaction BEFORE the authorization code is
-- exchanged: Stripe revokes the connection if a code is used twice, so a retry must never reach the exchange.
CREATE TABLE IF NOT EXISTS stripe_oauth_state (
    state            VARCHAR(64) PRIMARY KEY,
    merchant_pubkey  VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at      TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_stripe_oauth_state_expires_at ON stripe_oauth_state (expires_at);
