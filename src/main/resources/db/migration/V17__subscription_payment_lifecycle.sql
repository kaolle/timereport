ALTER TABLE subscription_accounts
    ADD COLUMN lifecycle_checked_at timestamptz;
CREATE INDEX subscription_maintenance_checked_idx
    ON subscription_accounts (lifecycle_checked_at NULLS FIRST, account_id)
    WHERE stripe_customer_id IS NOT NULL;

ALTER TABLE subscription_agreements
    ADD COLUMN price_model_id uuid,
    ADD COLUMN period_start timestamptz,
    ADD COLUMN period_end timestamptz,
    ADD COLUMN terminal_reason text,
    ADD FOREIGN KEY (plan, price_model_id) REFERENCES subscription_price_models (plan, id),
    ADD CHECK ((period_start IS NULL AND period_end IS NULL)
        OR (period_start IS NOT NULL AND period_end IS NOT NULL AND period_end > period_start)),
    ADD CHECK (terminal_reason IS NULL OR status = 'CANCELED');

ALTER TABLE subscription_paid_periods
    ADD COLUMN paid_at timestamptz;

ALTER TABLE subscription_checkout_attempts
    ADD COLUMN stripe_subscription_id text;
CREATE UNIQUE INDEX subscription_checkout_subscription_idx
    ON subscription_checkout_attempts (stripe_subscription_id)
    WHERE stripe_subscription_id IS NOT NULL;

-- Persist only the normalized event references, never the raw Stripe payload.
ALTER TABLE subscription_processed_events
    ADD COLUMN object_id text,
    ADD COLUMN occurred_at timestamptz,
    ADD COLUMN payload_sha256 bytea,
    ADD CHECK (payload_sha256 IS NULL OR octet_length(payload_sha256) = 32);

CREATE TABLE subscription_alerts
(
    account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    reference text NOT NULL,
    kind text NOT NULL,
    stripe_subscription_id text,
    created_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolution text,
    PRIMARY KEY (account_id, reference, kind),
    CHECK (length(reference) BETWEEN 1 AND 255),
    CHECK (length(kind) BETWEEN 1 AND 80),
    CHECK (resolved_at IS NULL OR resolution IS NOT NULL)
);
CREATE INDEX subscription_alerts_unresolved_idx ON subscription_alerts (created_at)
    WHERE resolved_at IS NULL;
