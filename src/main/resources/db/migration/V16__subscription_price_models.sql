CREATE TABLE subscription_price_models
(
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    plan text NOT NULL CHECK (plan IN ('MONTHLY', 'YEARLY')),
    amount numeric(12, 2) NOT NULL CHECK (amount > 0 AND amount <= 1000000),
    vat_percent numeric(5, 2) NOT NULL CHECK (vat_percent BETWEEN 0 AND 100),
    vat_inclusive boolean NOT NULL,
    state text NOT NULL CHECK (state IN ('CREATING', 'READY')),
    stripe_product_id text,
    stripe_price_id text UNIQUE,
    stripe_tax_rate_id text,
    created_by uuid NOT NULL REFERENCES accounts (id),
    created_at timestamptz NOT NULL,
    UNIQUE (plan, id),
    CHECK ((state = 'CREATING' AND stripe_product_id IS NULL AND stripe_price_id IS NULL AND stripe_tax_rate_id IS NULL)
        OR (state = 'READY' AND stripe_product_id IS NOT NULL AND stripe_price_id IS NOT NULL
            AND stripe_tax_rate_id IS NOT NULL AND left(stripe_product_id, 5) = 'prod_'
            AND left(stripe_price_id, 6) = 'price_' AND left(stripe_tax_rate_id, 4) = 'txr_'))
);

CREATE TABLE subscription_price_selections
(
    plan text PRIMARY KEY CHECK (plan IN ('MONTHLY', 'YEARLY')),
    price_model_id uuid,
    activated_at timestamptz,
    FOREIGN KEY (plan, price_model_id) REFERENCES subscription_price_models (plan, id)
);
INSERT INTO subscription_price_selections (plan) VALUES ('MONTHLY'), ('YEARLY');

-- Historical attempts keep their original Stripe configuration. New attempts
-- bind an immutable catalog price once before creating a Stripe Checkout session.
ALTER TABLE subscription_checkout_attempts
    ADD COLUMN price_model_id uuid,
    ADD COLUMN catalog_pricing boolean NOT NULL DEFAULT false,
    ADD FOREIGN KEY (plan, price_model_id) REFERENCES subscription_price_models (plan, id);
-- Keep the default false even after migration: an older Cloud Run revision can
-- still create an attempt during rollout. New code opts in explicitly on INSERT.
