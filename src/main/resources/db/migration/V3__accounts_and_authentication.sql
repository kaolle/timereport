-- Existing unowned records are test data; the owner explicitly approved this reset.
TRUNCATE time_entries, customer_codes, vacation_days;
CREATE TABLE accounts
(
    id            uuid PRIMARY KEY,
    email         text    NOT NULL UNIQUE,
    password_hash text    NOT NULL,
    verified      boolean NOT NULL DEFAULT false
);
CREATE TABLE auth_sessions
(
    id         uuid PRIMARY KEY,
    account_id uuid        NOT NULL REFERENCES accounts (id),
    expires_at timestamptz NOT NULL,
    revoked    boolean     NOT NULL DEFAULT false
);
CREATE INDEX auth_sessions_account_idx ON auth_sessions (account_id);
CREATE TABLE refresh_tokens
(
    token_hash text PRIMARY KEY,
    session_id uuid    NOT NULL REFERENCES auth_sessions (id),
    consumed   boolean NOT NULL DEFAULT false
);
CREATE TABLE account_tokens
(
    token_hash text PRIMARY KEY,
    account_id uuid        NOT NULL REFERENCES accounts (id),
    purpose    text        NOT NULL CHECK (purpose IN ('verify', 'reset')),
    expires_at timestamptz NOT NULL,
    consumed   boolean     NOT NULL DEFAULT false
);
CREATE TABLE auth_rate_limits
(
    bucket       text PRIMARY KEY,
    window_start timestamptz NOT NULL,
    hits         integer     NOT NULL
);
ALTER TABLE time_entries DROP CONSTRAINT time_entries_customer_code_fkey;
ALTER TABLE time_entries
    ADD COLUMN account_id uuid NOT NULL REFERENCES accounts (id);
ALTER TABLE customer_codes
    ADD COLUMN account_id uuid NOT NULL REFERENCES accounts (id);
ALTER TABLE vacation_days
    ADD COLUMN account_id uuid NOT NULL REFERENCES accounts (id);
ALTER TABLE customer_codes DROP CONSTRAINT customer_codes_pkey;
ALTER TABLE customer_codes
    ADD PRIMARY KEY (account_id, code);
ALTER TABLE time_entries DROP CONSTRAINT time_entries_pkey;
ALTER TABLE time_entries
    ADD PRIMARY KEY (account_id, id);
ALTER TABLE time_entries
    ADD FOREIGN KEY (account_id, customer_code) REFERENCES customer_codes (account_id, code);
ALTER TABLE vacation_days DROP CONSTRAINT vacation_days_pkey;
ALTER TABLE vacation_days
    ADD PRIMARY KEY (account_id, vacation_date);
DO
$$ DECLARE
constraint_name text;
BEGIN
    FOR constraint_name IN
SELECT conname
FROM pg_constraint
WHERE conrelid = 'time_entries'::regclass AND contype = 'x'
    LOOP EXECUTE format('ALTER TABLE time_entries DROP CONSTRAINT %I', constraint_name);
END LOOP;
END $$;
CREATE
EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE time_entries
    ADD CONSTRAINT account_time_entries_no_overlap EXCLUDE USING gist
    (account_id WITH =, tsrange(work_date + start_time, work_date + end_time, '[)') WITH &&) WHERE (NOT deleted);
DROP INDEX time_entries_work_date_idx;
CREATE INDEX time_entries_account_date_idx ON time_entries (account_id, work_date, start_time) WHERE NOT deleted;
DROP TABLE app_write_lock;
