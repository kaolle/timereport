package com.timereport.subscription.adapter.out.persistence;

import com.timereport.mail.application.port.in.QueueMail;
import com.timereport.mail.application.port.out.MailTransport;
import com.timereport.mail.domain.MailReference;
import com.timereport.subscription.application.port.in.SubscriptionRenewalReminders;
import com.timereport.subscription.domain.RenewalReminder;
import com.timereport.subscription.domain.RenewalReminderContext;
import com.timereport.subscription.domain.SubscriptionPlan;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class JdbcSubscriptionReminderRepositoryTest {
    @Inject DataSource dataSource;
    @Inject SubscriptionRenewalReminders reminders;
    @Inject JdbcSubscriptionReminderRepository repository;
    @Inject QueueMail queue;
    @InjectMock MailTransport transport;
    private final ArrayList<UUID> accounts = new ArrayList<>();

    @AfterEach
    void cleanup() throws SQLException {
        for (UUID account : accounts) {
            execute("DELETE FROM subscription_renewal_reminders WHERE account_id=?", account);
            execute("DELETE FROM mail_outbox WHERE account_id=?", account);
            execute("DELETE FROM subscription_agreements WHERE account_id=?", account);
            execute("DELETE FROM accounts WHERE id=?", account);
        }
    }

    @Test
    void parallelWorkersQueueOneDurableMessageAndCancellationIsReferenceScoped() throws Exception {
        doThrow(new IllegalStateException("simulated transport failure")).when(transport).send(any(), any());
        var context = context();
        var barrier = new CyclicBarrier(2);
        var count = new AtomicInteger();
        var loader = Thread.currentThread().getContextClassLoader();
        try (var workers = Executors.newFixedThreadPool(2, task -> {
            var thread = new Thread(task);
            thread.setContextClassLoader(loader);
            return thread;
        })) {
            var first = workers.submit(() -> runWorker(context, barrier, count));
            var second = workers.submit(() -> runWorker(context, barrier, count));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, count.get());
        assertEquals(1, count("SELECT COUNT(*) FROM subscription_renewal_reminders WHERE account_id=?", context.accountId()));
        assertEquals(1, count("SELECT COUNT(*) FROM mail_outbox WHERE account_id=?", context.accountId()));
        var reminderId = RenewalReminder.idFor(context);
        QuarkusTransaction.requiringNew().run(() -> {
            var stored = repository.find(context.accountId(), reminderId).orElseThrow();
            assertEquals(RenewalReminder.State.QUEUED, stored.state());
            assertTrue(repository.find(UUID.randomUUID(), reminderId).isEmpty());
            assertFalse(queue.cancel(new MailReference(UUID.randomUUID(), "subscription-renewal", stored.mailId())));
            assertFalse(queue.cancel(new MailReference(context.accountId(), "invoice", stored.mailId())));
        });
        locked(context.accountId(), () -> reminders.sync(context.toBuilder().renewing(false).build(), Instant.now()));
        assertEquals(1, count("SELECT COUNT(*) FROM mail_outbox WHERE account_id=? AND state='CANCELLED'", context.accountId()));
        QuarkusTransaction.requiringNew().run(() -> assertEquals(RenewalReminder.State.CANCELLED,
                repository.find(context.accountId(), reminderId).orElseThrow().state()));
        verify(transport, times(1)).send(any(), any());
    }

    @Test
    void reminderScheduleAndEncryptedQueueRollBackTogetherOnFailure() throws SQLException {
        var context = context();
        assertThrows(IllegalStateException.class, () -> locked(context.accountId(), () -> {
            reminders.sync(context, Instant.now());
            throw new IllegalStateException("roll back reminder and outbox");
        }));
        assertEquals(0, count("SELECT COUNT(*) FROM subscription_renewal_reminders WHERE account_id=?", context.accountId()));
        assertEquals(0, count("SELECT COUNT(*) FROM mail_outbox WHERE account_id=?", context.accountId()));
        verifyNoInteractions(transport);
    }

    @Test
    void successfulDeliveryRecordsTerminalStatusAndCannotBeQueuedAgain() throws SQLException {
        var context = context();
        locked(context.accountId(), () -> assertEquals(1, reminders.sync(context, Instant.now())));
        QuarkusTransaction.requiringNew().run(() -> assertEquals(RenewalReminder.State.SENT,
                repository.find(context.accountId(), RenewalReminder.idFor(context)).orElseThrow().state()));
        locked(context.accountId(), () -> assertEquals(0, reminders.sync(context, Instant.now())));
        assertEquals(1, count("SELECT COUNT(*) FROM mail_outbox WHERE account_id=? AND state='SENT'", context.accountId()));
        verify(transport, times(1)).send(any(), any());
    }

    private void runWorker(RenewalReminderContext context, CyclicBarrier barrier, AtomicInteger count) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        locked(context.accountId(), () -> count.addAndGet(reminders.sync(context, Instant.now())));
    }

    private void locked(UUID accountId, Runnable work) {
        QuarkusTransaction.requiringNew().run(() -> {
            try (var connection = dataSource.getConnection(); var lock = connection.prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE")) {
                lock.setObject(1, accountId);
                try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
                work.run();
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        });
    }

    private RenewalReminderContext context() throws SQLException {
        UUID account = UUID.randomUUID();
        execute("INSERT INTO accounts (id,email,password_hash,verified) VALUES (?,?,'test',true)", account, account + "@example.test");
        accounts.add(account);
        execute("INSERT INTO subscription_agreements (id,account_id,stripe_subscription_id,plan,status) "
                + "VALUES (?,?,?,'MONTHLY','ACTIVE')", UUID.randomUUID(), account, "sub_reminder_" + account);
        return RenewalReminderContext.builder().accountId(account).subscriptionId("sub_reminder_" + account)
                .plan(SubscriptionPlan.MONTHLY).renewalAt(Instant.now().plus(6, ChronoUnit.DAYS))
                .amountIncludingVat(new BigDecimal("61.25")).recipientEmail(account + "@example.test").renewing(true).build();
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private int count(String sql, UUID accountId) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
        }
    }
}
