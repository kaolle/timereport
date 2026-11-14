CREATE TABLE account_roles
(
    account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('admin')),
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, role)
);

-- Every authenticated account has the implicit user role. Administrative roles
-- are assigned explicitly by account ID; email addresses never grant access.
