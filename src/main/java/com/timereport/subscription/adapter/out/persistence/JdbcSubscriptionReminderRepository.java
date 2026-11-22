package com.timereport.subscription.adapter.out.persistence;

import com.timereport.subscription.application.port.out.SubscriptionReminderRepository;
import com.timereport.subscription.domain.RenewalReminder;
import com.timereport.subscription.domain.SubscriptionPlan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JdbcSubscriptionReminderRepository implements SubscriptionReminderRepository {
    private final DataSource dataSource;

    public JdbcSubscriptionReminderRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public List<RenewalReminder> pending(UUID accountId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT * FROM subscription_renewal_reminders WHERE account_id=? "
                        + "AND state IN ('PLANNED','QUEUED') ORDER BY due_at,id")) {
            statement.setObject(1, accountId);
            try (var result = statement.executeQuery()) {
                var reminders = new ArrayList<RenewalReminder>();
                while (result.next()) reminders.add(from(result));
                return List.copyOf(reminders);
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<RenewalReminder> find(UUID accountId, UUID reminderId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT * FROM subscription_renewal_reminders WHERE account_id=? AND id=?")) {
            statement.setObject(1, accountId);
            statement.setObject(2, reminderId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(from(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void save(RenewalReminder reminder, Instant now) {
        String sql = """
                INSERT INTO subscription_renewal_reminders
                    (id,account_id,stripe_subscription_id,plan,renewal_at,due_at,amount_including_vat,
                     state,generation,mail_id,queued_at,completed_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET due_at=EXCLUDED.due_at,
                    amount_including_vat=EXCLUDED.amount_including_vat,state=EXCLUDED.state,
                    generation=EXCLUDED.generation,mail_id=EXCLUDED.mail_id,queued_at=EXCLUDED.queued_at,
                    completed_at=EXCLUDED.completed_at,updated_at=EXCLUDED.updated_at
                WHERE subscription_renewal_reminders.account_id=EXCLUDED.account_id
                  AND subscription_renewal_reminders.stripe_subscription_id=EXCLUDED.stripe_subscription_id
                  AND subscription_renewal_reminders.plan=EXCLUDED.plan
                  AND subscription_renewal_reminders.renewal_at=EXCLUDED.renewal_at
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, reminder.id());
            statement.setObject(2, reminder.accountId());
            statement.setString(3, reminder.subscriptionId());
            statement.setString(4, reminder.plan().name());
            statement.setTimestamp(5, Timestamp.from(reminder.renewalAt()));
            statement.setTimestamp(6, Timestamp.from(reminder.dueAt()));
            statement.setBigDecimal(7, reminder.amountIncludingVat());
            statement.setString(8, reminder.state().name());
            statement.setInt(9, reminder.generation());
            statement.setObject(10, reminder.mailId());
            statement.setTimestamp(11, timestamp(reminder.queuedAt()));
            statement.setTimestamp(12, timestamp(reminder.completedAt()));
            statement.setTimestamp(13, Timestamp.from(now));
            statement.setTimestamp(14, Timestamp.from(now));
            if (statement.executeUpdate() != 1)
                throw new IllegalStateException("Renewal reminder belongs to another account or subscription");
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void completed(UUID accountId, UUID mailId, RenewalReminder.State state, Instant completedAt) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE subscription_renewal_reminders SET state=?,completed_at=?,updated_at=? "
                        + "WHERE account_id=? AND mail_id=? AND state='QUEUED'")) {
            statement.setString(1, state.name());
            statement.setTimestamp(2, Timestamp.from(completedAt));
            statement.setTimestamp(3, Timestamp.from(completedAt));
            statement.setObject(4, accountId);
            statement.setObject(5, mailId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    private RenewalReminder from(ResultSet result) throws SQLException {
        return RenewalReminder.builder().id(result.getObject("id", UUID.class))
                .accountId(result.getObject("account_id", UUID.class)).subscriptionId(result.getString("stripe_subscription_id"))
                .plan(SubscriptionPlan.valueOf(result.getString("plan"))).renewalAt(result.getTimestamp("renewal_at").toInstant())
                .dueAt(result.getTimestamp("due_at").toInstant()).amountIncludingVat(result.getBigDecimal("amount_including_vat"))
                .state(RenewalReminder.State.valueOf(result.getString("state"))).generation(result.getInt("generation"))
                .mailId(result.getObject("mail_id", UUID.class)).queuedAt(instant(result.getTimestamp("queued_at")))
                .completedAt(instant(result.getTimestamp("completed_at"))).build();
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static IllegalStateException databaseError(SQLException failure) {
        return new IllegalStateException("Renewal reminder database operation failed", failure);
    }
}
