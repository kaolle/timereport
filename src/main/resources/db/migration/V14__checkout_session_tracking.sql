ALTER TABLE subscription_accounts
    ADD COLUMN customer_idempotency_key text NOT NULL DEFAULT gen_random_uuid()::text UNIQUE;

ALTER TABLE subscription_checkout_attempts
    ADD COLUMN expires_at timestamptz,
    ADD COLUMN resolved_at timestamptz,
    ADD CONSTRAINT subscription_open_checkout_complete
        CHECK (status <> 'OPEN' OR
               (stripe_session_id IS NOT NULL AND expires_at IS NOT NULL));
