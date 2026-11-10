package com.timereport.subscription.adapter.out.persistence;

import com.timereport.subscription.application.service.SubscriptionPurchaseConflict;
import com.timereport.subscription.domain.CheckoutAttempt;
import com.timereport.subscription.domain.SubscriptionPlan;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class JdbcSubscriptionPurchaseRepositoryTest {
    @Inject DataSource dataSource;
    @Inject JdbcSubscriptionPurchaseRepository repository;

    @Test
    void reservesOneCheckoutAndPreservesHistoryForALaterPurchase() throws SQLException {
        UUID account = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            execute("INSERT INTO accounts (id, email, password_hash, verified) VALUES (?, ?, 'test', true)",
                    account, account + "@example.test");
            var first = repository.reserve(account, SubscriptionPlan.MONTHLY, now);
            assertEquals(CheckoutAttempt.State.CREATING, first.state());
            assertEquals(first.id(), repository.reserve(account, SubscriptionPlan.MONTHLY, now).id());
            assertEquals(SubscriptionPlan.MONTHLY, repository.reserve(account, SubscriptionPlan.YEARLY, now).plan());
            assertEquals("cus_test", repository.saveCustomerId(account, "cus_test"));
            assertEquals("cus_test", repository.customerId(account).orElseThrow());
            assertFalse(repository.customerIdempotencyKey(account).isBlank());

            repository.open(first.id(), "cs_test_first", now.plusSeconds(3600));
            var open = repository.reserve(account, SubscriptionPlan.MONTHLY, now);
            assertEquals(CheckoutAttempt.State.OPEN, open.state());
            assertEquals("cs_test_first", open.stripeSessionId());
            assertEquals(first.id(), repository.reserve(account, SubscriptionPlan.MONTHLY,
                    now.plusSeconds(7200)).id());
            repository.complete(first.id());
            assertThrows(SubscriptionPurchaseConflict.class,
                    () -> repository.reserve(account, SubscriptionPlan.MONTHLY, now));

            execute("UPDATE subscription_checkout_attempts SET resolved_at = ? WHERE id = ?",
                    Timestamp.from(now), first.id());
            var second = repository.reserve(account, SubscriptionPlan.YEARLY, now);
            assertNotEquals(first.id(), second.id());
            assertEquals(SubscriptionPlan.YEARLY, second.plan());
            assertEquals(2, count("SELECT COUNT(*) FROM subscription_checkout_attempts WHERE account_id = ?", account));
        } finally {
            execute("DELETE FROM subscription_checkout_attempts WHERE account_id = ?", account);
            execute("DELETE FROM subscription_accounts WHERE account_id = ?", account);
            execute("DELETE FROM accounts WHERE id = ?", account);
        }
    }

    @Test
    void aPaidPeriodBlocksAnotherCheckoutEvenAfterAgreementCancellation() throws SQLException {
        UUID account = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            execute("INSERT INTO accounts (id, email, password_hash, verified) VALUES (?, ?, 'test', true)",
                    account, account + "@example.test");
            execute("INSERT INTO subscription_agreements "
                            + "(id, account_id, stripe_subscription_id, plan, status) "
                            + "VALUES (?, ?, 'sub_paid', 'MONTHLY', 'CANCELED')", UUID.randomUUID(), account);
            execute("INSERT INTO subscription_paid_periods "
                            + "(id, account_id, stripe_invoice_id, stripe_subscription_id, period_start, period_end) "
                            + "VALUES (?, ?, ?, 'sub_paid', ?, ?)", UUID.randomUUID(), account,
                    "in_" + UUID.randomUUID(), Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now.plusSeconds(3600)));
            assertThrows(SubscriptionPurchaseConflict.class,
                    () -> repository.reserve(account, SubscriptionPlan.MONTHLY, now));
        } finally {
            execute("DELETE FROM subscription_paid_periods WHERE account_id = ?", account);
            execute("DELETE FROM subscription_agreements WHERE account_id = ?", account);
            execute("DELETE FROM subscription_accounts WHERE account_id = ?", account);
            execute("DELETE FROM accounts WHERE id = ?", account);
        }
    }

    @Test
    void simultaneousRequestsFromTwoTabsShareOneCheckoutReservation() throws Exception {
        UUID account = UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(2);
        try {
            execute("INSERT INTO accounts (id, email, password_hash, verified) VALUES (?, ?, 'test', true)",
                    account, account + "@example.test");
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return repository.reserve(account, SubscriptionPlan.MONTHLY, Instant.now());
            });
            var second = executor.submit(() -> {
                start.await();
                return repository.reserve(account, SubscriptionPlan.MONTHLY, Instant.now());
            });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS).id(), second.get(10, TimeUnit.SECONDS).id());
            assertEquals(1, count("SELECT COUNT(*) FROM subscription_checkout_attempts WHERE account_id = ?", account));
        } finally {
            executor.shutdownNow();
            execute("DELETE FROM subscription_checkout_attempts WHERE account_id = ?", account);
            execute("DELETE FROM subscription_accounts WHERE account_id = ?", account);
            execute("DELETE FROM accounts WHERE id = ?", account);
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private int count(String sql, UUID accountId) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
