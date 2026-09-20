-- 398ja/cashu-mint#462 — record whether the mint was actually told about a payment.
--
-- A settled payment is forwarded to the mint by HttpMintWebhookForwarder, which retries
-- three times and then returns false. PhoenixWebhookHandler.forwardToMint discarded that
-- boolean, so a payment the mint never heard about looked exactly like one it did: the
-- quote sat PAID, the customer's money was taken, and the only trace was a log line.
-- Seven such quotes accumulated on staging, one of them against a mint_quote still
-- reading UNPAID while the adapter held the money.
--
-- Nothing on either side recorded the forward, so nothing could reconcile it. This column
-- is that record: NULL on a PAID quote means "money taken, mint not told", which is what
-- PaidQuoteForwardReconciler sweeps and payment_adapter_paid_unforwarded counts.
--
-- Nullable on purpose, for two different reasons:
--   * NULL is the signal itself, not merely an absence.
--   * Every row predating this column has a genuinely unknown forwarding state. Back-filling
--     them with now() would assert a forward that may never have happened and would hide the
--     seven known stranded payments; leaving them NULL keeps them visible. The sweep's grace
--     period is what stops it re-delivering the entire historical table on first run.
--
-- The `quote` table is managed by Hibernate ddl-auto, not by an earlier Flyway migration,
-- and Flyway runs first — so on a fresh database this is a no-op and Hibernate creates the
-- table already carrying the column. Same guard as V6 for the same reason.
--
-- Idempotent via IF NOT EXISTS so re-runs are safe.
ALTER TABLE IF EXISTS quote ADD COLUMN IF NOT EXISTS mint_notified_at TIMESTAMP;

-- The sweep and the gauge both ask the same question: which PAID quotes have no forward
-- recorded? Without this they scan the whole table every minute, and `quote` grows without
-- bound (4374 PENDING rows on staging already).
CREATE INDEX IF NOT EXISTS idx_quote_paid_unforwarded
    ON quote (state, mint_notified_at);
