package com.timereport.mail.adapter.out.persistence;

import com.timereport.mail.application.port.out.MailOutbox;
import com.timereport.mail.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JdbcMailOutbox implements MailOutbox {
    private final DataSource dataSource;
    private final EncryptedMailPayload codec;

    public JdbcMailOutbox(DataSource dataSource, EncryptedMailPayload codec) {
        this.dataSource = dataSource;
        this.codec = codec;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void enqueue(MailReference ref, MailMessage message, Instant now, Instant expiresAt) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO mail_outbox(id,account_id,type,state,payload,created_at,expires_at,next_attempt_at) VALUES(?,?,?,'PENDING',?,?,?,?)")) {
            statement.setObject(1, ref.id());
            statement.setObject(2, ref.accountId());
            statement.setString(3, ref.type());
            statement.setBytes(4, codec.encrypt(message));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(expiresAt));
            statement.setTimestamp(7, Timestamp.from(now));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Mail could not be queued", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void cancel(UUID accountId, String type, Instant now) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE mail_outbox SET state='CANCELLED',payload=NULL,completed_at=?,next_attempt_at=NULL "
                        + "WHERE account_id=? AND type=? AND state='PENDING'")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, accountId);
            statement.setString(3, type);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Mail cancellation failed", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<MailClaim> claim(Instant now, Instant leaseUntil) {
        UUID lease = UUID.randomUUID();
        String sql = "WITH candidate AS (SELECT id FROM mail_outbox WHERE "
                + "(state='PENDING' AND next_attempt_at<=?) OR (state='SENDING' AND lease_until<=?) "
                + "ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) "
                + "UPDATE mail_outbox m SET state='SENDING',lease_until=?,lease_token=?,attempts=m.attempts+1 "
                + "FROM candidate WHERE m.id=candidate.id "
                + "RETURNING m.id,m.account_id,m.type,m.payload,m.expires_at,m.attempts";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(leaseUntil));
            statement.setObject(4, lease);
            try (var result = statement.executeQuery()) {
                return claimed(result, lease);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Mail claim failed", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<MailClaim> claim(MailReference reference, Instant now, Instant leaseUntil) {
        UUID lease = UUID.randomUUID();
        String sql = "WITH candidate AS (SELECT id FROM mail_outbox WHERE id=? AND account_id=? AND type=? "
                + "AND state='PENDING' AND next_attempt_at<=? FOR UPDATE SKIP LOCKED) "
                + "UPDATE mail_outbox m SET state='SENDING',lease_until=?,lease_token=?,attempts=m.attempts+1 "
                + "FROM candidate WHERE m.id=candidate.id "
                + "RETURNING m.id,m.account_id,m.type,m.payload,m.expires_at,m.attempts";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, reference.id());
            statement.setObject(2, reference.accountId());
            statement.setString(3, reference.type());
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(leaseUntil));
            statement.setObject(6, lease);
            try (var result = statement.executeQuery()) {
                return claimed(result, lease);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Immediate mail claim failed", e);
        }
    }

    private Optional<MailClaim> claimed(java.sql.ResultSet result, UUID lease) throws SQLException {
        if (!result.next()) return Optional.empty();
        return Optional.of(MailClaim.builder()
                .reference(new MailReference(result.getObject("account_id", UUID.class), result.getString("type"), result.getObject("id", UUID.class)))
                .message(codec.decrypt(result.getBytes("payload")))
                .expiresAt(result.getTimestamp("expires_at").toInstant())
                .attempt(result.getInt("attempts")).leaseToken(lease).build());
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean finish(MailClaim claim, MailState state, Instant now, Instant nextAttemptAt, String errorCode) {
        if (state == MailState.SENDING) throw new IllegalArgumentException("Invalid mail result");
        String sql = "UPDATE mail_outbox SET state=?,payload=CASE WHEN ? THEN NULL ELSE payload END,"
                + "completed_at=?,next_attempt_at=?,lease_until=NULL,lease_token=NULL,error_code=? "
                + "WHERE id=? AND account_id=? AND state='SENDING' AND lease_token=?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.name());
            statement.setBoolean(2, state.terminal());
            statement.setTimestamp(3, state.terminal() ? Timestamp.from(now) : null);
            statement.setTimestamp(4, state == MailState.PENDING ? Timestamp.from(nextAttemptAt) : null);
            statement.setString(5, errorCode);
            statement.setObject(6, claim.reference().id());
            statement.setObject(7, claim.reference().accountId());
            statement.setObject(8, claim.leaseToken());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Mail completion failed", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRED)
    public int deleteCompletedBefore(Instant cutoff) {
        String sql = "DELETE FROM mail_outbox WHERE state IN ('SENT','FAILED','EXPIRED','CANCELLED') "
                + "AND completed_at <= ?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Mail cleanup failed", e);
        }
    }
}
