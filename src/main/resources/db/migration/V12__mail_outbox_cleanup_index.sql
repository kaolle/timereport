CREATE INDEX mail_outbox_completed_cleanup_idx ON mail_outbox (completed_at)
    WHERE state IN ('SENT', 'FAILED', 'EXPIRED', 'CANCELLED');
