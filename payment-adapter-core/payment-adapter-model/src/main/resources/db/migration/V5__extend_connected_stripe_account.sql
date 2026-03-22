ALTER TABLE connected_stripe_account ADD COLUMN details_submitted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE connected_stripe_account ADD COLUMN requirements_due VARCHAR(4096);
ALTER TABLE connected_stripe_account ADD COLUMN disabled_reason VARCHAR(255);
ALTER TABLE connected_stripe_account ADD COLUMN country VARCHAR(8);
ALTER TABLE connected_stripe_account ADD COLUMN email VARCHAR(255);
