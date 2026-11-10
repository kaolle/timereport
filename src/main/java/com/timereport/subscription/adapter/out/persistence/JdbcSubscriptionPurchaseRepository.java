package com.timereport.subscription.adapter.out.persistence;

import com.timereport.subscription.application.port.out.SubscriptionPurchaseRepository;
import com.timereport.subscription.application.service.SubscriptionPurchaseConflict;
import com.timereport.subscription.domain.CheckoutAttempt;
import com.timereport.subscription.domain.SubscriptionPlan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JdbcSubscriptionPurchaseRepository implements SubscriptionPurchaseRepository {
    private final DataSource dataSource;

    public JdbcSubscriptionPurchaseRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @Transactional
    public CheckoutAttempt reserve(UUID accountId, SubscriptionPlan plan, Instant now) {
        try (var connection = dataSource.getConnection()) {
            // Serialize checkout decisions for one account across all Cloud Run instances.
            try (var lock = connection.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, accountId);
                try (var rows = lock.executeQuery()) {
                    if (!rows.next()) throw new SubscriptionPurchaseConflict("Kontot finns inte längre.");
                }
            }
            try (var live = connection.prepareStatement("SELECT 1 FROM subscription_agreements "
                    + "WHERE account_id = ? AND status IN ('PENDING', 'ACTIVE', 'PAST_DUE') LIMIT 1")) {
                live.setObject(1, accountId);
                try (var rows = live.executeQuery()) {
                    if (rows.next()) throw new SubscriptionPurchaseConflict("Kontot har redan ett pågående abonnemang.");
                }
            }
            try (var completed = connection.prepareStatement("SELECT 1 FROM subscription_checkout_attempts "
                    + "WHERE account_id = ? AND status = 'COMPLETED' AND resolved_at IS NULL LIMIT 1")) {
                completed.setObject(1, accountId);
                try (var rows = completed.executeQuery()) {
                    if (rows.next()) throw new SubscriptionPurchaseConflict("Betalningen behandlas. Vänta på bekräftelse.");
                }
            }
            try (var paid = connection.prepareStatement("SELECT 1 FROM subscription_paid_periods "
                    + "WHERE account_id = ? AND period_end > ? AND voided_at IS NULL LIMIT 1")) {
                paid.setObject(1, accountId);
                paid.setTimestamp(2, Timestamp.from(now));
                try (var rows = paid.executeQuery()) {
                    if (rows.next()) throw new SubscriptionPurchaseConflict("Kontot har redan en betald abonnemangsperiod.");
                }
            }
            try (var insert = connection.prepareStatement("INSERT INTO subscription_accounts (account_id) "
                    + "VALUES (?) ON CONFLICT (account_id) DO NOTHING")) {
                insert.setObject(1, accountId);
                insert.executeUpdate();
            }
            try (var find = connection.prepareStatement("SELECT id, plan, status, idempotency_key, "
                    + "stripe_session_id, expires_at, created_at FROM subscription_checkout_attempts "
                    + "WHERE account_id = ? AND status IN ('CREATING', 'OPEN') ORDER BY created_at DESC LIMIT 1")) {
                find.setObject(1, accountId);
                try (var rows = find.executeQuery()) {
                    if (rows.next()) {
                        var attempt = fromRow(accountId, rows);
                        var created = rows.getTimestamp("created_at").toInstant();
                        // An elapsed local expiry is not enough: Checkout may have completed
                        // before expiry while its webhook is still in flight.
                        boolean expired = attempt.state() == CheckoutAttempt.State.CREATING
                                && created.isBefore(now.minus(25, ChronoUnit.HOURS));
                        if (!expired) return attempt;
                        expireInConnection(connection, attempt.id());
                    }
                }
            }
            UUID id = UUID.randomUUID();
            String key = "timereport-checkout-" + id;
            try (var insert = connection.prepareStatement("INSERT INTO subscription_checkout_attempts "
                    + "(id, account_id, plan, status, idempotency_key) VALUES (?, ?, ?, 'CREATING', ?)")) {
                insert.setObject(1, id);
                insert.setObject(2, accountId);
                insert.setString(3, plan.name());
                insert.setString(4, key);
                insert.executeUpdate();
            }
            return CheckoutAttempt.builder().id(id).accountId(accountId).plan(plan)
                    .state(CheckoutAttempt.State.CREATING).idempotencyKey(key).build();
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    public Optional<String> customerId(UUID accountId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT stripe_customer_id FROM subscription_accounts WHERE account_id = ?")) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    public String customerIdempotencyKey(UUID accountId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT customer_idempotency_key FROM subscription_accounts WHERE account_id = ?")) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Abonnemangskonto saknas.");
                return rows.getString(1);
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    @Transactional
    public String saveCustomerId(UUID accountId, String stripeCustomerId) {
        try (var connection = dataSource.getConnection(); var update = connection.prepareStatement(
                "UPDATE subscription_accounts SET stripe_customer_id = ? "
                        + "WHERE account_id = ? AND stripe_customer_id IS NULL")) {
            update.setString(1, stripeCustomerId);
            update.setObject(2, accountId);
            update.executeUpdate();
            return customerId(accountId).orElseThrow(() -> new IllegalStateException("Kundkoppling saknas."));
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    @Transactional
    public void open(UUID attemptId, String stripeSessionId, Instant expiresAt) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE subscription_checkout_attempts SET status = 'OPEN', stripe_session_id = ?, "
                        + "expires_at = ?, updated_at = now() WHERE id = ? AND status = 'CREATING'")) {
            statement.setString(1, stripeSessionId);
            statement.setTimestamp(2, Timestamp.from(expiresAt));
            statement.setObject(3, attemptId);
            if (statement.executeUpdate() != 1)
                throw new SubscriptionPurchaseConflict("Köpsessionen kunde inte kopplas till kontot.");
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    @Transactional
    public void complete(UUID attemptId) {
        changeState(attemptId, "COMPLETED");
    }

    @Override
    @Transactional
    public void expire(UUID attemptId) {
        changeState(attemptId, "EXPIRED");
    }

    private void changeState(UUID attemptId, String state) {
        try (var connection = dataSource.getConnection()) {
            expireInConnection(connection, attemptId, state);
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    private void expireInConnection(Connection connection, UUID attemptId) throws SQLException {
        expireInConnection(connection, attemptId, "EXPIRED");
    }

    private void expireInConnection(Connection connection, UUID attemptId, String state) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE subscription_checkout_attempts "
                + "SET status = ?, updated_at = now() WHERE id = ? AND status IN ('CREATING', 'OPEN')")) {
            update.setString(1, state);
            update.setObject(2, attemptId);
            if (update.executeUpdate() != 1)
                throw new SubscriptionPurchaseConflict("Köpsessionens status har ändrats. Försök igen.");
        }
    }

    private CheckoutAttempt fromRow(UUID accountId, ResultSet rows) throws SQLException {
        var expiry = rows.getTimestamp("expires_at");
        return CheckoutAttempt.builder().id(rows.getObject("id", UUID.class)).accountId(accountId)
                .plan(SubscriptionPlan.valueOf(rows.getString("plan")))
                .state(CheckoutAttempt.State.valueOf(rows.getString("status")))
                .idempotencyKey(rows.getString("idempotency_key"))
                .stripeSessionId(rows.getString("stripe_session_id"))
                .expiresAt(expiry == null ? null : expiry.toInstant()).build();
    }

    private IllegalStateException databaseError(SQLException cause) {
        return new IllegalStateException("Databasåtgärden för abonnemang misslyckades.", cause);
    }
}
