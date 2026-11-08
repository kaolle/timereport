package com.timereport.subscription.adapter.out.persistence;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class JdbcSubscriptionRepositoryTest {
    @Inject DataSource dataSource;
    @Inject JdbcSubscriptionRepository repository;

    @Test
    void paidPeriodsAndOwnerGrantsBelongOnlyToTheirOwnAccount() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID subscriber = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID grant = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        try {
            for (UUID id : new UUID[]{owner, subscriber, outsider}) {
                execute("INSERT INTO accounts (id, email, password_hash, verified) VALUES (?, ?, 'test', true)",
                        id, id + "@example.test");
            }
            execute("INSERT INTO subscription_access_grants (id, account_id, kind, granted_by, reason) "
                    + "VALUES (?, ?, 'OWNER', 'test', 'owner access test')", grant, owner);
            execute("INSERT INTO subscription_accounts (account_id) VALUES (?)", subscriber);
            execute("INSERT INTO subscription_agreements "
                    + "(id, account_id, stripe_subscription_id, plan, status, created_at) "
                    + "VALUES (?, ?, 'sub_test', 'MONTHLY', 'ACTIVE', ?)",
                    UUID.randomUUID(), subscriber, now.minusSeconds(120));
            assertThrows(SQLException.class, () -> execute("INSERT INTO subscription_paid_periods "
                    + "(id, account_id, stripe_invoice_id, stripe_subscription_id, period_start, period_end) "
                    + "VALUES (?, ?, ?, 'sub_test', ?, ?)", UUID.randomUUID(), owner,
                    UUID.randomUUID().toString(), now.minusSeconds(60), now.plusSeconds(60)));
            execute("INSERT INTO subscription_paid_periods "
                    + "(id, account_id, stripe_invoice_id, stripe_subscription_id, period_start, period_end) "
                    + "VALUES (?, ?, ?, 'sub_test', ?, ?)", UUID.randomUUID(), subscriber, UUID.randomUUID().toString(),
                    now.minusSeconds(60), now.plusSeconds(60));

            assertTrue(repository.status(owner).canInvoiceAt(now));
            assertTrue(repository.status(subscriber).canInvoiceAt(now));
            assertFalse(repository.status(outsider).canInvoiceAt(now));
            assertFalse(repository.status(subscriber).canInvoiceAt(now.plusSeconds(60)));
            execute("UPDATE subscription_agreements SET status = 'CANCELED', ended_at = ? "
                    + "WHERE account_id = ? AND stripe_subscription_id = 'sub_test'", now.plusSeconds(60), subscriber);
            execute("INSERT INTO subscription_agreements "
                    + "(id, account_id, stripe_subscription_id, plan, status, created_at) "
                    + "VALUES (?, ?, 'sub_second', 'MONTHLY', 'ACTIVE', ?)",
                    UUID.randomUUID(), subscriber, now.plusSeconds(120));
            execute("INSERT INTO subscription_paid_periods "
                    + "(id, account_id, stripe_invoice_id, stripe_subscription_id, period_start, period_end) "
                    + "VALUES (?, ?, ?, 'sub_second', ?, ?)", UUID.randomUUID(), subscriber,
                    UUID.randomUUID().toString(), now.plusSeconds(120), now.plusSeconds(2678400));
            assertTrue(repository.status(subscriber).canInvoiceAt(now.plusSeconds(180)));
            assertEquals(2, count("SELECT COUNT(*) FROM subscription_agreements WHERE account_id = ?", subscriber));
            execute("UPDATE subscription_access_grants SET revoked_at = now(), revoked_by = 'test', "
                    + "revoke_reason = 'test revocation' WHERE id = ?", grant);
            assertFalse(repository.status(owner).canInvoiceAt(now));
        } finally {
            execute("DELETE FROM subscription_paid_periods WHERE account_id = ?", subscriber);
            execute("DELETE FROM subscription_agreements WHERE account_id = ?", subscriber);
            execute("DELETE FROM subscription_accounts WHERE account_id = ?", subscriber);
            execute("DELETE FROM subscription_access_grants WHERE account_id = ?", owner);
            for (UUID id : new UUID[]{owner, subscriber, outsider})
                execute("DELETE FROM accounts WHERE id = ?", id);
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++)
                statement.setObject(i + 1, values[i] instanceof Instant instant
                        ? java.sql.Timestamp.from(instant) : values[i]);
            statement.executeUpdate();
        }
    }

    private int count(String sql, Object value) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
