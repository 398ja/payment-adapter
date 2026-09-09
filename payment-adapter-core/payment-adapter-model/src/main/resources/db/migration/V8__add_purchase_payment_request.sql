-- The id a discharge is verified against.
--
-- imani-woo ADR 0009: "fulfilment is confirmed, not asserted". The gateway can
-- answer whether a COMPLETED send answered a given payment request, so a
-- discharge stops being the issuer's word and becomes something checkable.
--
-- Generated when the debt is recorded, and deliberately not by the issuer. The
-- party owed an answer mints the id before the work happens, so it cannot be
-- chosen after the fact to fit whatever happened. An issuer-generated id would
-- make the check circular.
ALTER TABLE stripe_purchase ADD COLUMN payment_request_id VARCHAR(255);

-- A plain UNIQUE constraint, NOT a partial index.
--
-- The first version of this migration used `CREATE UNIQUE INDEX ... WHERE
-- payment_request_id IS NOT NULL`, which is PostgreSQL-only and fails on the H2
-- database the tests run against. Every context in payment-adapter-rest died on
-- "Detected failed migration to version 8" rather than on anything to do with
-- purchases.
--
-- No behaviour is lost: the SQL standard lets multiple NULLs coexist under a
-- UNIQUE constraint, which is exactly what the partial index was for. Rows
-- without an id stay unconstrained, and two debts still cannot share one id —
-- which matters because one send answering two requests is how a customer pays
-- twice and is served once.
ALTER TABLE stripe_purchase
    ADD CONSTRAINT uq_stripe_purchase_payment_request UNIQUE (payment_request_id);
