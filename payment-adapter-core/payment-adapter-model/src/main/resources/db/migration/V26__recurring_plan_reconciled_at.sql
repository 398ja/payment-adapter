-- When the instalment reconciler last claimed a plan. It is both the rotation order (least recently checked first,
-- so a bounded run reaches every plan) and a per-plan lease: the reconciler claims a plan with one conditional UPDATE
-- and a replica that loses the claim skips the plan, so two replicas never work the same plan in one window.
ALTER TABLE recurring_plan ADD COLUMN IF NOT EXISTS reconciled_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX IF NOT EXISTS idx_recurring_plan_reconciled ON recurring_plan (reconciled_at);
