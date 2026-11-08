CREATE TABLE subscription_accounts (
    account_id uuid PRIMARY KEY REFERENCES accounts (id),
    stripe_customer_id text UNIQUE
);

-- A returning account receives a new agreement; old paid periods remain linked
-- to the agreement that produced them.
CREATE TABLE subscription_agreements (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts (id),
    stripe_subscription_id text NOT NULL UNIQUE,
    plan text CHECK (plan IN ('MONTHLY', 'YEARLY')),
    status text NOT NULL CHECK (status IN ('NONE', 'PENDING', 'ACTIVE', 'PAST_DUE', 'CANCELED')),
    grace_until timestamptz,
    cancel_at_period_end boolean NOT NULL DEFAULT false,
    ended_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, stripe_subscription_id)
);
CREATE UNIQUE INDEX subscription_one_live_agreement_per_account_idx
    ON subscription_agreements (account_id)
    WHERE status IN ('PENDING', 'ACTIVE', 'PAST_DUE');

CREATE TABLE subscription_paid_periods (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts (id),
    stripe_invoice_id text NOT NULL UNIQUE,
    stripe_subscription_id text NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    voided_at timestamptz,
    void_reason text,
    FOREIGN KEY (account_id, stripe_subscription_id)
        REFERENCES subscription_agreements (account_id, stripe_subscription_id),
    CHECK (voided_at IS NULL OR void_reason IS NOT NULL),
    CHECK (period_end > period_start)
);
CREATE INDEX subscription_paid_periods_account_end_idx
    ON subscription_paid_periods (account_id, period_end DESC);

CREATE TABLE subscription_checkout_attempts (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts (id),
    plan text NOT NULL CHECK (plan IN ('MONTHLY', 'YEARLY')),
    status text NOT NULL CHECK (status IN ('CREATING', 'OPEN', 'COMPLETED', 'EXPIRED')),
    idempotency_key text NOT NULL UNIQUE,
    stripe_session_id text UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX subscription_one_open_checkout_per_account_idx
    ON subscription_checkout_attempts (account_id) WHERE status IN ('CREATING', 'OPEN');

CREATE TABLE subscription_processed_events (
    stripe_event_id text PRIMARY KEY,
    event_type text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    payload bytea NOT NULL
);

CREATE TABLE subscription_access_grants (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts (id),
    kind text NOT NULL CHECK (kind = 'OWNER'),
    granted_at timestamptz NOT NULL DEFAULT now(),
    granted_by text NOT NULL,
    reason text NOT NULL,
    revoked_at timestamptz,
    revoked_by text,
    revoke_reason text,
    CHECK (revoked_at IS NULL OR (revoked_by IS NOT NULL AND revoke_reason IS NOT NULL))
);
CREATE UNIQUE INDEX subscription_one_active_owner_grant_idx
    ON subscription_access_grants (account_id) WHERE revoked_at IS NULL;

-- One-time grant to the existing owner's account ID. Future accounts with the
-- same email do not inherit access; runtime checks use only the account ID.
INSERT INTO subscription_access_grants (id, account_id, kind, granted_by, reason)
SELECT '537b1f0d-c596-455b-bb4c-539de2c9e0d2', id, 'OWNER',
       'migration V13', 'Existing Tidrapporten owner retains invoicing access'
FROM accounts WHERE lower(email) = 'kaolle@gmail.com';
