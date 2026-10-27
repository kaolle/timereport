CREATE TABLE invoice_status
(
    account_id     uuid    NOT NULL,
    invoice_number integer NOT NULL,
    sent_at        timestamptz,
    paid_at        timestamptz,
    version        bigint  NOT NULL CHECK (version > 0),
    PRIMARY KEY (account_id, invoice_number),
    FOREIGN KEY (account_id, invoice_number) REFERENCES issued_invoices (account_id, invoice_number)
);

CREATE TABLE invoice_deliveries
(
    account_id     uuid         NOT NULL,
    id             uuid         NOT NULL,
    invoice_number integer      NOT NULL,
    recipient      varchar(254) NOT NULL,
    state          varchar(10)  NOT NULL CHECK (state IN ('SENDING', 'SENT', 'FAILED')),
    requested_at   timestamptz  NOT NULL,
    completed_at   timestamptz,
    PRIMARY KEY (account_id, id),
    FOREIGN KEY (account_id, invoice_number) REFERENCES issued_invoices (account_id, invoice_number),
    CHECK ((state = 'SENDING' AND completed_at IS NULL) OR (state <> 'SENDING' AND completed_at IS NOT NULL))
);
CREATE INDEX invoice_deliveries_invoice_idx ON invoice_deliveries (account_id, invoice_number, requested_at DESC);
