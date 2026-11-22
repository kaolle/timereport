package com.timereport.subscription.adapter.out.persistence;

import com.timereport.subscription.application.port.out.SubscriptionLifecycleRepository;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.application.service.SubscriptionPurchaseConflict;
import com.timereport.subscription.domain.BillingEvent;
import com.timereport.subscription.domain.BillingInvoice;
import com.timereport.subscription.domain.SubscriptionAgreement;
import com.timereport.subscription.domain.SubscriptionCheckoutBinding;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@ApplicationScoped
public class JdbcSubscriptionLifecycleRepository implements SubscriptionLifecycleRepository {
    private final DataSource dataSource;

    public JdbcSubscriptionLifecycleRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public <T> T locked(UUID accountId, Supplier<T> work) {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(work, "work is required");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT id FROM accounts WHERE id=? FOR UPDATE")) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SubscriptionPurchaseConflict("Kontot finns inte längre.");
            }
            return work.get();
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    public Optional<UUID> accountForCustomer(String customerId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT account_id FROM subscription_accounts WHERE stripe_customer_id=?")) {
            statement.setString(1, customerId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean beginEvent(BillingEvent event) {
        byte[] payload = (event.id() + "\n" + event.type() + "\n" + event.objectId() + "\n"
                + event.createdAt()).getBytes(StandardCharsets.UTF_8);
        byte[] hash = sha256(payload);
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("INSERT INTO subscription_processed_events "
                    + "(stripe_event_id,event_type,payload,object_id,occurred_at,payload_sha256) "
                    + "VALUES (?,?,?,?,?,?) ON CONFLICT(stripe_event_id) DO NOTHING")) {
                statement.setString(1, event.id());
                statement.setString(2, event.type());
                statement.setBytes(3, payload);
                statement.setString(4, event.objectId());
                statement.setTimestamp(5, timestamp(event.createdAt()));
                statement.setBytes(6, hash);
                if (statement.executeUpdate() == 1) return true;
            }
            try (var statement = connection.prepareStatement("SELECT payload_sha256,processed_at "
                    + "FROM subscription_processed_events WHERE stripe_event_id=? FOR UPDATE")) {
                statement.setString(1, event.id());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !Arrays.equals(hash, rows.getBytes("payload_sha256")))
                        throw new SubscriptionBillingUnavailable("Stripe-händelsens referenser har ändrats.");
                    return rows.getTimestamp("processed_at") == null;
                }
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void completeEvent(String eventId, Instant now) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE subscription_processed_events SET processed_at=COALESCE(processed_at,?) WHERE stripe_event_id=?")) {
            statement.setTimestamp(1, timestamp(now));
            statement.setString(2, eventId);
            if (statement.executeUpdate() != 1)
                throw new IllegalStateException("Stripe-händelsen saknas.");
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    public Optional<SubscriptionAgreement> agreement(UUID accountId, String subscriptionId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT * FROM subscription_agreements WHERE account_id=? AND stripe_subscription_id=?")) {
            statement.setObject(1, accountId);
            statement.setString(2, subscriptionId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readAgreement(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    public Optional<SubscriptionCheckoutBinding> binding(UUID accountId, UUID attemptId, String sessionId) {
        if (attemptId == null && sessionId == null)
            throw new IllegalArgumentException("En köpreferens krävs.");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT c.id,c.account_id,c.stripe_session_id,c.price_model_id,c.catalog_pricing,p.stripe_price_id "
                        + "FROM subscription_checkout_attempts c LEFT JOIN subscription_price_models p ON p.id=c.price_model_id "
                        + "WHERE c.account_id=? AND (?::uuid IS NULL OR c.id=?) "
                        + "AND (?::text IS NULL OR c.stripe_session_id=?)")) {
            statement.setObject(1, accountId);
            statement.setObject(2, attemptId);
            statement.setObject(3, attemptId);
            statement.setString(4, sessionId);
            statement.setString(5, sessionId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(SubscriptionCheckoutBinding.builder()
                        .accountId(rows.getObject("account_id", UUID.class))
                        .attemptId(rows.getObject("id", UUID.class))
                        .sessionId(rows.getString("stripe_session_id"))
                        .priceModelId(rows.getObject("price_model_id", UUID.class))
                        .priceId(rows.getString("stripe_price_id"))
                        .catalogPricing(rows.getBoolean("catalog_pricing")).build()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void saveAgreement(SubscriptionAgreement agreement) {
        try (var connection = dataSource.getConnection()) {
            try (var check = connection.prepareStatement("SELECT id,account_id,terminal_reason FROM subscription_agreements "
                    + "WHERE stripe_subscription_id=? FOR UPDATE")) {
                check.setString(1, agreement.subscriptionId());
                try (var rows = check.executeQuery()) {
                    if (rows.next()) {
                        if (!agreement.accountId().equals(rows.getObject("account_id", UUID.class))
                                || !agreement.id().equals(rows.getObject("id", UUID.class)))
                            throw new SubscriptionBillingUnavailable("Abonnemangets kontokoppling stämmer inte.");
                        if (rows.getString("terminal_reason") != null && agreement.state() != SubscriptionState.CANCELED)
                            throw new SubscriptionBillingUnavailable("Ett avslutat abonnemang kan inte återaktiveras.");
                    }
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO subscription_agreements "
                    + "(id,account_id,stripe_subscription_id,price_model_id,plan,status,grace_until,cancel_at_period_end,"
                    + "period_start,period_end,ended_at,terminal_reason,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT(stripe_subscription_id) DO UPDATE SET price_model_id=EXCLUDED.price_model_id,"
                    + "plan=EXCLUDED.plan,status=EXCLUDED.status,grace_until=EXCLUDED.grace_until,"
                    + "cancel_at_period_end=EXCLUDED.cancel_at_period_end,period_start=EXCLUDED.period_start,"
                    + "period_end=EXCLUDED.period_end,ended_at=COALESCE(subscription_agreements.ended_at,EXCLUDED.ended_at),"
                    + "terminal_reason=COALESCE(subscription_agreements.terminal_reason,EXCLUDED.terminal_reason),"
                    + "updated_at=EXCLUDED.updated_at WHERE subscription_agreements.account_id=EXCLUDED.account_id")) {
                statement.setObject(1, agreement.id());
                statement.setObject(2, agreement.accountId());
                statement.setString(3, agreement.subscriptionId());
                statement.setObject(4, agreement.priceModelId());
                statement.setString(5, agreement.plan().name());
                statement.setString(6, agreement.state().name());
                statement.setTimestamp(7, timestamp(agreement.graceUntil()));
                statement.setBoolean(8, agreement.cancelAtPeriodEnd());
                statement.setTimestamp(9, timestamp(agreement.periodStart()));
                statement.setTimestamp(10, timestamp(agreement.periodEnd()));
                statement.setTimestamp(11, timestamp(agreement.endedAt()));
                statement.setString(12, agreement.terminalReason());
                statement.setTimestamp(13, timestamp(agreement.updatedAt()));
                if (statement.executeUpdate() != 1)
                    throw new SubscriptionBillingUnavailable("Abonnemangets kontokoppling stämmer inte.");
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void recordPaidPeriod(UUID accountId, String subscriptionId, BillingInvoice invoice) {
        if (!subscriptionId.equals(invoice.subscriptionId()) || invoice.state() != BillingInvoice.State.PAID)
            throw new IllegalArgumentException("En verifierad betald faktura krävs.");
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("INSERT INTO subscription_paid_periods "
                    + "(id,account_id,stripe_invoice_id,stripe_subscription_id,period_start,period_end,paid_at) "
                    + "VALUES (?,?,?,?,?,?,?) ON CONFLICT(stripe_invoice_id) DO NOTHING")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, accountId);
                statement.setString(3, invoice.id());
                statement.setString(4, subscriptionId);
                statement.setTimestamp(5, timestamp(invoice.periodStart()));
                statement.setTimestamp(6, timestamp(invoice.periodEnd()));
                statement.setTimestamp(7, timestamp(invoice.paidAt()));
                if (statement.executeUpdate() == 1) return;
            }
            try (var statement = connection.prepareStatement("SELECT account_id,stripe_subscription_id,period_start,period_end,paid_at "
                    + "FROM subscription_paid_periods WHERE stripe_invoice_id=?")) {
                statement.setString(1, invoice.id());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !accountId.equals(rows.getObject("account_id", UUID.class))
                            || !subscriptionId.equals(rows.getString("stripe_subscription_id"))
                            || !invoice.periodStart().equals(instant(rows, "period_start"))
                            || !invoice.periodEnd().equals(instant(rows, "period_end"))
                            || !Objects.equals(invoice.paidAt(), instant(rows, "paid_at")))
                        throw new SubscriptionBillingUnavailable("Fakturans betalda period stämmer inte med historiken.");
                }
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void resolveCheckout(UUID accountId, UUID attemptId, String subscriptionId, Instant now) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE subscription_checkout_attempts SET status='COMPLETED',resolved_at=COALESCE(resolved_at,?),"
                        + "stripe_subscription_id=?,updated_at=? WHERE account_id=? AND id=? "
                        + "AND status IN ('CREATING','OPEN','COMPLETED') "
                        + "AND (stripe_subscription_id IS NULL OR stripe_subscription_id=?)")) {
            statement.setTimestamp(1, timestamp(now));
            statement.setString(2, subscriptionId);
            statement.setTimestamp(3, timestamp(now));
            statement.setObject(4, accountId);
            statement.setObject(5, attemptId);
            statement.setString(6, subscriptionId);
            if (statement.executeUpdate() != 1)
                throw new SubscriptionBillingUnavailable("Köpet kunde inte kopplas till abonnemanget.");
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    public List<UUID> maintenanceCandidates(UUID afterAccountId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Ogiltig batchstorlek.");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT account_id FROM subscription_accounts WHERE stripe_customer_id IS NOT NULL "
                        + "AND (?::uuid IS NULL OR account_id>?) "
                        + "ORDER BY lifecycle_checked_at NULLS FIRST,account_id LIMIT ?")) {
            statement.setObject(1, afterAccountId);
            statement.setObject(2, afterAccountId);
            statement.setInt(3, limit);
            var ids = new ArrayList<UUID>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getObject(1, UUID.class));
            }
            return List.copyOf(ids);
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markMaintenanceChecked(UUID accountId, Instant now) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE subscription_accounts SET lifecycle_checked_at=? WHERE account_id=?")) {
            statement.setTimestamp(1, timestamp(Objects.requireNonNull(now, "now is required")));
            statement.setObject(2, Objects.requireNonNull(accountId, "accountId is required"));
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    public List<String> subscriptionIds(UUID accountId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT stripe_subscription_id FROM subscription_agreements WHERE account_id=? ORDER BY created_at,id")) {
            statement.setObject(1, accountId);
            var ids = new ArrayList<String>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
            return List.copyOf(ids);
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    public Optional<String> customerId(UUID accountId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT stripe_customer_id FROM subscription_accounts WHERE account_id=?")) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    public Optional<Instant> paidUntil(UUID accountId, String subscriptionId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT MAX(period_end) FROM subscription_paid_periods WHERE account_id=? AND stripe_subscription_id=? "
                        + "AND voided_at IS NULL AND period_start<=CURRENT_TIMESTAMP")) {
            statement.setObject(1, accountId);
            statement.setString(2, subscriptionId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(instant(rows, 1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    public boolean ownerAccess(UUID accountId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT 1 FROM subscription_access_grants WHERE account_id=? AND kind='OWNER' AND revoked_at IS NULL")) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void alert(UUID accountId, String reference, String kind, String subscriptionId, Instant now) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO subscription_alerts(account_id,reference,kind,stripe_subscription_id,created_at) "
                        + "VALUES (?,?,?,?,?) ON CONFLICT(account_id,reference,kind) DO NOTHING")) {
            statement.setObject(1, accountId);
            statement.setString(2, reference);
            statement.setString(3, kind);
            statement.setString(4, subscriptionId);
            statement.setTimestamp(5, timestamp(now));
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    private SubscriptionAgreement readAgreement(ResultSet rows) throws SQLException {
        var plan = rows.getString("plan");
        return SubscriptionAgreement.builder().id(rows.getObject("id", UUID.class))
                .accountId(rows.getObject("account_id", UUID.class)).subscriptionId(rows.getString("stripe_subscription_id"))
                .priceModelId(rows.getObject("price_model_id", UUID.class)).plan(plan == null ? null : SubscriptionPlan.valueOf(plan))
                .state(SubscriptionState.valueOf(rows.getString("status"))).graceUntil(instant(rows, "grace_until"))
                .cancelAtPeriodEnd(rows.getBoolean("cancel_at_period_end")).periodStart(instant(rows, "period_start"))
                .periodEnd(instant(rows, "period_end")).endedAt(instant(rows, "ended_at"))
                .terminalReason(rows.getString("terminal_reason")).updatedAt(instant(rows, "updated_at")).build();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rows, String name) throws SQLException {
        var value = rows.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rows, int index) throws SQLException {
        var value = rows.getTimestamp(index);
        return value == null ? null : value.toInstant();
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 saknas.", impossible);
        }
    }

    private IllegalStateException databaseError(SQLException failure) {
        return new IllegalStateException("Databasåtgärden för abonnemangets betalstatus misslyckades.", failure);
    }
}
