CREATE TABLE invoice_number_sequences
(
    account_id  uuid PRIMARY KEY REFERENCES accounts (id),
    next_number integer NOT NULL CHECK (next_number BETWEEN 1 AND 1000000000)
);

CREATE TABLE issued_invoices
(
    id             uuid        NOT NULL,
    account_id     uuid        NOT NULL REFERENCES accounts (id),
    invoice_number integer     NOT NULL CHECK (invoice_number BETWEEN 1 AND 999999999),
    period_month   char(7)     NOT NULL,
    customer_id    uuid        NOT NULL,
    invoice_date   date        NOT NULL,
    due_date       date        NOT NULL,
    issued_at      timestamptz NOT NULL,
    snapshot       jsonb       NOT NULL,
    pdf            bytea       NOT NULL,
    PRIMARY KEY (account_id, id),
    CONSTRAINT issued_invoices_number_unique UNIQUE (account_id, invoice_number),
    CONSTRAINT issued_invoices_customer_period_unique UNIQUE (account_id, customer_id, period_month)
);
