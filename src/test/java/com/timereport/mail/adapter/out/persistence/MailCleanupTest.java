package com.timereport.mail.adapter.out.persistence;

import com.timereport.mail.application.port.in.CleanMailOutbox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class MailCleanupTest {
    @Inject DataSource dataSource;
    @Inject CleanMailOutbox cleaner;

    @Test
    void deletesOnlyTerminalRowsCompletedMoreThanSixtyDaysAgo() throws Exception {
        var accountId = UUID.randomUUID();
        var now = Instant.now();
        insertAccount(accountId);
        try {
            var oldSent = insertMail(accountId, "SENT", now.minus(Duration.ofDays(61)));
            var oldFailed = insertMail(accountId, "FAILED", now.minus(Duration.ofDays(61)));
            var oldExpired = insertMail(accountId, "EXPIRED", now.minus(Duration.ofDays(61)));
            var oldCancelled = insertMail(accountId, "CANCELLED", now.minus(Duration.ofDays(61)));
            var recentSent = insertMail(accountId, "SENT", now.minus(Duration.ofDays(59)));
            var pending = insertMail(accountId, "PENDING", null);
            var sending = insertMail(accountId, "SENDING", null);

            assertEquals(4, cleaner.clean());
            assertEquals(0, count(oldSent, oldFailed, oldExpired, oldCancelled));
            assertEquals(3, count(recentSent, pending, sending));
            assertEquals(0, cleaner.clean());
        } finally {
            deleteAccountAndMail(accountId);
        }
    }

    private void insertAccount(UUID accountId) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO accounts(id,email,password_hash,verified) VALUES(?,?,?,true)")) {
            statement.setObject(1, accountId);
            statement.setString(2, accountId + "@example.test");
            statement.setString(3, "test");
            statement.executeUpdate();
        }
    }

    private UUID insertMail(UUID accountId, String state, Instant completedAt) throws Exception {
        var id = UUID.randomUUID();
        var createdAt = Instant.now().minus(Duration.ofDays(62));
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO mail_outbox(id,account_id,type,state,payload,created_at,expires_at,"
                        + "next_attempt_at,lease_until,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            statement.setObject(1, id);
            statement.setObject(2, accountId);
            statement.setString(3, "test");
            statement.setString(4, state);
            statement.setBytes(5, completedAt == null ? new byte[]{1} : null);
            statement.setTimestamp(6, Timestamp.from(createdAt));
            statement.setTimestamp(7, Timestamp.from(Instant.now().plus(Duration.ofDays(1))));
            statement.setTimestamp(8, completedAt == null ? Timestamp.from(Instant.now().plus(Duration.ofDays(1))) : null);
            statement.setTimestamp(9, "SENDING".equals(state) ? Timestamp.from(Instant.now().plus(Duration.ofDays(1))) : null);
            statement.setTimestamp(10, completedAt == null ? null : Timestamp.from(completedAt));
            statement.executeUpdate();
        }
        return id;
    }

    private int count(UUID... ids) throws Exception {
        int found = 0;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT 1 FROM mail_outbox WHERE id=?")) {
            for (var id : ids) {
                statement.setObject(1, id);
                try (var result = statement.executeQuery()) {
                    if (result.next()) found++;
                }
            }
        }
        return found;
    }

    private void deleteAccountAndMail(UUID accountId) throws Exception {
        try (var connection = dataSource.getConnection(); var mail = connection.prepareStatement(
                "DELETE FROM mail_outbox WHERE account_id=?"); var account = connection.prepareStatement(
                "DELETE FROM accounts WHERE id=?")) {
            mail.setObject(1, accountId);
            mail.executeUpdate();
            account.setObject(1, accountId);
            account.executeUpdate();
        }
    }
}
