-- Card atomicity audit (2026-10-15), scenario S1c: one PAYMENT, one debt.
--
-- stripe_purchase was unique only on event_id. One paid Checkout Session can be
-- announced by two different events (checkout.session.completed with
-- payment_status=paid, then checkout.session.async_payment_succeeded), and each
-- became its own OWED row. purchase-issuer keys its mint on the event id, so it
-- minted one coupon per row: two vouchers for one card payment. Reproduced on a
-- local stack (card-atomicity harness, S1c: rows=2, mints=2, same pi_).
--
-- The listener now looks the session up first (an ordinary outcome, logged), and
-- these constraints are the last line of defence when two replicas race.
--
-- payment_intent_id is nullable (a Checkout in payment mode always has one, but
-- older rows may not), and Postgres lets a UNIQUE index hold any number of NULLs.
--
-- If this migration FAILS on deploy, duplicate rows already exist: that is a
-- double-issued payment, and a person must look before it is retried
-- (SELECT checkout_session_id, count(*) FROM stripe_purchase GROUP BY 1 HAVING count(*) > 1).
DROP INDEX IF EXISTS idx_stripe_purchase_session;
CREATE UNIQUE INDEX IF NOT EXISTS uq_stripe_purchase_session ON stripe_purchase (checkout_session_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_stripe_purchase_payment_intent ON stripe_purchase (payment_intent_id);
