ALTER TABLE billing_customers ADD COLUMN contact_email text NOT NULL DEFAULT '';
ALTER TABLE invoice_deliveries
    ADD COLUMN cc text[] NOT NULL DEFAULT '{}',
    ADD COLUMN bcc text[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT invoice_deliveries_cc_limit CHECK (cardinality(cc) <= 10),
    ADD CONSTRAINT invoice_deliveries_bcc_limit CHECK (cardinality(bcc) <= 10);
