-- PayPal instalment plans (docs/adr/0003-paypal-instalment-plans.md).
--
-- recurring_plan is shared by both processors, as stripe_purchase is (ADR 0002): a plan means "this buyer pays this
-- stall in instalments, and each paid one issues vouchers", whoever moves the money. For a PayPal plan the columns
-- keep their Stripe names and take the PayPal equivalent:
--   connected_account_id  the seller's PayPal merchant id
--   subscription_id       PayPal's subscription id (I-...), known as soon as the plan starts: Imani creates the
--                         subscription itself, before the buyer sees PayPal
--   checkout_session_id   unused (null)
-- An instalment's stripe_purchase row (processor='paypal') holds the SALE id in both checkout_session_id and
-- payment_intent_id, and recurring_plan_id names the plan, so "one row per paid instalment" stays a database fact.
ALTER TABLE recurring_plan ADD COLUMN IF NOT EXISTS processor VARCHAR(16) NOT NULL DEFAULT 'stripe';
CREATE INDEX IF NOT EXISTS idx_recurring_plan_processor_status ON recurring_plan (processor, status);
