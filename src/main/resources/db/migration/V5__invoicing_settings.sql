CREATE TABLE billing_profiles (
    account_id uuid PRIMARY KEY REFERENCES accounts (id),
    seller_name text NOT NULL,
    address_line text NOT NULL,
    postal_code text NOT NULL,
    city text NOT NULL,
    country text NOT NULL,
    organization_number text NOT NULL,
    vat_number text NOT NULL,
    bankgiro text NOT NULL DEFAULT '',
    email text NOT NULL DEFAULT '',
    currency_code char(3) NOT NULL DEFAULT 'SEK',
    payment_terms_days integer NOT NULL DEFAULT 30 CHECK (payment_terms_days BETWEEN 0 AND 365),
    default_vat_percent numeric(5,2) NOT NULL DEFAULT 25.00 CHECK (default_vat_percent BETWEEN 0 AND 100),
    f_tax_registered boolean NOT NULL DEFAULT false
);

CREATE TABLE billing_customers (
    id uuid NOT NULL,
    account_id uuid NOT NULL REFERENCES accounts (id),
    customer_number text NOT NULL,
    name text NOT NULL,
    address_line text NOT NULL,
    postal_code text NOT NULL,
    city text NOT NULL,
    country text NOT NULL,
    organization_number text NOT NULL DEFAULT '',
    vat_number text NOT NULL DEFAULT '',
    invoice_email text NOT NULL DEFAULT '',
    customer_reference text NOT NULL DEFAULT '',
    our_reference text NOT NULL DEFAULT '',
    payment_terms_days integer NOT NULL DEFAULT 30 CHECK (payment_terms_days BETWEEN 0 AND 365),
    PRIMARY KEY (account_id, id),
    UNIQUE (account_id, customer_number)
);

CREATE TABLE billing_customer_codes (
    account_id uuid NOT NULL,
    code text NOT NULL,
    customer_id uuid NOT NULL,
    service_description text NOT NULL,
    hourly_rate numeric(12,2) NOT NULL CHECK (hourly_rate >= 0),
    vat_percent numeric(5,2) NOT NULL CHECK (vat_percent BETWEEN 0 AND 100),
    PRIMARY KEY (account_id, code),
    FOREIGN KEY (account_id, code) REFERENCES customer_codes (account_id, code),
    FOREIGN KEY (account_id, customer_id) REFERENCES billing_customers (account_id, id)
);
