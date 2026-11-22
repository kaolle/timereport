CREATE TABLE subscription_renewal_reminders (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts (id),
    stripe_subscription_id text NOT NULL,
    plan text NOT NULL CHECK (plan IN ('MONTHLY', 'YEARLY')),
    renewal_at timestamptz NOT NULL,
    due_at timestamptz NOT NULL,
    amount_including_vat numeric(14,2) NOT NULL CHECK (amount_including_vat >= 0),
    state text NOT NULL CHECK (state IN ('PLANNED', 'QUEUED', 'SENT', 'FAILED', 'EXPIRED', 'CANCELLED')),
    generation integer NOT NULL DEFAULT 0 CHECK (generation >= 0),
    mail_id uuid NOT NULL UNIQUE,
    queued_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (stripe_subscription_id, renewal_at, plan),
    FOREIGN KEY (account_id, stripe_subscription_id)
        REFERENCES subscription_agreements (account_id, stripe_subscription_id),
    CHECK (due_at < renewal_at)
);
CREATE INDEX subscription_renewal_reminders_pending_idx
    ON subscription_renewal_reminders (account_id, due_at)
    WHERE state IN ('PLANNED', 'QUEUED');
