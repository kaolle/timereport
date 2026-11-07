CREATE TABLE mail_outbox
(
    id              uuid PRIMARY KEY,
    account_id      uuid         NOT NULL REFERENCES accounts (id),
    type            varchar(40)  NOT NULL,
    state           varchar(10)  NOT NULL CHECK (state IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'EXPIRED', 'CANCELLED')),
    payload         bytea,
    created_at      timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    next_attempt_at timestamptz,
    lease_until     timestamptz,
    lease_token     uuid,
    attempts        integer      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    completed_at    timestamptz,
    error_code      varchar(40),
    CHECK (expires_at > created_at),
    CHECK ((state IN ('PENDING', 'SENDING') AND payload IS NOT NULL)
        OR (state NOT IN ('PENDING', 'SENDING') AND payload IS NULL))
);
CREATE INDEX mail_outbox_pending_idx ON mail_outbox (state, next_attempt_at, lease_until);
CREATE INDEX mail_outbox_account_idx ON mail_outbox (account_id, type, created_at DESC);
