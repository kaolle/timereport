package com.timereport.authentication.adapter.out.persistence;

import com.timereport.authentication.application.port.out.AuthRepository;
import com.timereport.authentication.domain.Account;
import com.timereport.authentication.domain.AccountToken;
import com.timereport.authentication.domain.RefreshToken;
import com.timereport.authentication.domain.Session;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@ApplicationScoped
public class JdbcAuthRepository implements AuthRepository {
    private final DataSource dataSource;

    public JdbcAuthRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @Transactional
    public <T> T atomic(Supplier<T> work) {
        try (var connection = dataSource.getConnection();
             var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(804171)")) {
            lock.execute();
            return work.get();
        } catch (SQLException e) {
            throw new IllegalStateException("Authentication storage failed", e);
        }
    }

    private interface Mapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private <T> Optional<T> one(String sql, Mapper<T> mapper, Object... args) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapper.map(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Authentication storage failed", e);
        }
    }

    private void update(String sql, Object... args) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Authentication storage failed", e);
        }
    }

    private void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i] instanceof Instant instant ? Timestamp.from(instant) : args[i]);
        }
    }

    private Account mapAccount(ResultSet r) throws SQLException {
        return Account.builder().id(r.getObject("id", UUID.class)).email(r.getString("email"))
                .passwordHash(r.getString("password_hash")).verified(r.getBoolean("verified")).build();
    }

    @Override
    public Optional<Account> account(String email) {
        return one("SELECT * FROM accounts WHERE email = ?", this::mapAccount, email);
    }

    @Override
    public Optional<Account> account(UUID id) {
        return one("SELECT * FROM accounts WHERE id = ?", this::mapAccount, id);
    }

    @Override
    public void save(Account a) {
        update("INSERT INTO accounts(id,email,password_hash,verified) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET password_hash=excluded.password_hash, verified=excluded.verified",
                a.id(), a.email(), a.passwordHash(), a.verified());
    }

    @Override
    public Optional<Session> session(UUID id) {
        return one("SELECT * FROM auth_sessions WHERE id = ?", r -> Session.builder()
                .id(r.getObject("id", UUID.class)).accountId(r.getObject("account_id", UUID.class))
                .expiresAt(r.getObject("expires_at", OffsetDateTime.class).toInstant()).revoked(r.getBoolean("revoked")).build(), id);
    }

    @Override
    public void save(Session s) {
        update("INSERT INTO auth_sessions(id,account_id,expires_at,revoked) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET revoked=excluded.revoked",
                s.id(), s.accountId(), s.expiresAt(), s.revoked());
    }

    @Override
    public Optional<RefreshToken> refresh(String hash) {
        return one("SELECT * FROM refresh_tokens WHERE token_hash = ?", r -> RefreshToken.builder()
                .hash(r.getString("token_hash")).sessionId(r.getObject("session_id", UUID.class)).consumed(r.getBoolean("consumed")).build(), hash);
    }

    @Override
    public void save(RefreshToken t) {
        update("INSERT INTO refresh_tokens(token_hash,session_id,consumed) VALUES (?,?,?) ON CONFLICT(token_hash) DO UPDATE SET consumed=excluded.consumed",
                t.hash(), t.sessionId(), t.consumed());
    }

    @Override
    public Optional<AccountToken> token(String hash) {
        return one("SELECT * FROM account_tokens WHERE token_hash = ?", r -> AccountToken.builder()
                .hash(r.getString("token_hash")).accountId(r.getObject("account_id", UUID.class)).purpose(r.getString("purpose"))
                .expiresAt(r.getObject("expires_at", OffsetDateTime.class).toInstant()).consumed(r.getBoolean("consumed")).build(), hash);
    }

    @Override
    public void save(AccountToken t) {
        update("INSERT INTO account_tokens(token_hash,account_id,purpose,expires_at,consumed) VALUES (?,?,?,?,?)",
                t.hash(), t.accountId(), t.purpose(), t.expiresAt(), t.consumed());
    }

    @Override
    public void revokeSessions(UUID id) {
        update("UPDATE auth_sessions SET revoked=true WHERE account_id=?", id);
    }

    @Override
    public void consumeTokens(UUID id, String purpose) {
        update("UPDATE account_tokens SET consumed=true WHERE account_id=? AND purpose=?", id, purpose);
    }
}
