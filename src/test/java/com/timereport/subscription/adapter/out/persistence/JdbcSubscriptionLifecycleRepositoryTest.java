package com.timereport.subscription.adapter.out.persistence;

import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.domain.BillingEvent;
import com.timereport.subscription.domain.BillingInvoice;
import com.timereport.subscription.domain.SubscriptionAgreement;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class JdbcSubscriptionLifecycleRepositoryTest {
    @Inject DataSource dataSource;
    @Inject JdbcSubscriptionLifecycleRepository repository;
    @Inject JdbcSubscriptionRepository access;

    @Test
    void eventAndSubscriptionChangesCommitTogetherAndDuplicatesDoNotRepeatEffects() throws Exception {
        try (var fixture = new Fixture()) {
            var event = fixture.event();
            assertThrows(IllegalStateException.class, () -> repository.locked(fixture.account, () -> {
                assertTrue(repository.beginEvent(event));
                repository.saveAgreement(fixture.agreement());
                repository.completeEvent(event.id(), fixture.now);
                throw new IllegalStateException("rollback test");
            }));
            assertEquals(0, count("SELECT COUNT(*) FROM subscription_processed_events WHERE stripe_event_id=?", event.id()));
            assertTrue(repository.agreement(fixture.account, fixture.subscription).isEmpty());

            repository.locked(fixture.account, () -> {
                assertTrue(repository.beginEvent(event));
                repository.saveAgreement(fixture.agreement());
                repository.recordPaidPeriod(fixture.account, fixture.subscription, fixture.invoice());
                repository.completeEvent(event.id(), fixture.now);
                return null;
            });
            repository.locked(fixture.account, () -> {
                assertFalse(repository.beginEvent(event));
                return null;
            });
            assertEquals(1, count("SELECT COUNT(*) FROM subscription_paid_periods WHERE account_id=?", fixture.account));
            var altered = BillingEvent.builder().id(event.id()).type(event.type())
                    .objectId("sub_other").createdAt(event.createdAt()).build();
            assertThrows(SubscriptionBillingUnavailable.class,
                    () -> repository.locked(fixture.account, () -> repository.beginEvent(altered)));
        }
    }

    @Test
    void paidPeriodsAreIdempotentAndCannotBeReassignedOrChanged() throws Exception {
        try (var first = new Fixture(); var second = new Fixture()) {
            repository.locked(first.account, () -> {
                repository.saveAgreement(first.agreement());
                repository.recordPaidPeriod(first.account, first.subscription, first.invoice());
                repository.recordPaidPeriod(first.account, first.subscription, first.invoice());
                return null;
            });
            repository.locked(second.account, () -> {
                repository.saveAgreement(second.agreement());
                return null;
            });
            assertEquals(1, count("SELECT COUNT(*) FROM subscription_paid_periods WHERE account_id=?", first.account));
            assertEquals(first.periodEnd, repository.paidUntil(first.account, first.subscription).orElseThrow());
            assertTrue(repository.paidUntil(second.account, first.subscription).isEmpty());

            var changed = BillingInvoice.builder().id(first.invoiceId).subscriptionId(first.subscription)
                    .customerId(first.customer).priceId(first.price).state(BillingInvoice.State.PAID)
                    .periodStart(first.periodStart).periodEnd(first.periodEnd.plusSeconds(1))
                    .paidAt(first.now).amountPaid(4900).total(4900).build();
            assertThrows(SubscriptionBillingUnavailable.class, () -> repository.locked(first.account, () -> {
                repository.recordPaidPeriod(first.account, first.subscription, changed);
                return null;
            }));
            var reassigned = BillingInvoice.builder().id(first.invoiceId).subscriptionId(second.subscription)
                    .customerId(second.customer).priceId(second.price).state(BillingInvoice.State.PAID)
                    .periodStart(first.periodStart).periodEnd(first.periodEnd).paidAt(first.now)
                    .amountPaid(4900).total(4900).build();
            assertThrows(SubscriptionBillingUnavailable.class, () -> repository.locked(second.account, () -> {
                repository.recordPaidPeriod(second.account, second.subscription, reassigned);
                return null;
            }));
        }
    }

    @Test
    void terminalAgreementsKeepTheirTombstoneAndPaidHistory() throws Exception {
        try (var fixture = new Fixture()) {
            var original = fixture.agreement();
            repository.locked(fixture.account, () -> {
                repository.saveAgreement(original);
                repository.recordPaidPeriod(fixture.account, fixture.subscription, fixture.invoice());
                repository.saveAgreement(original.toBuilder().state(SubscriptionState.CANCELED)
                        .terminalReason("REMOTE_CANCELED").endedAt(fixture.now).build());
                return null;
            });
            assertThrows(SubscriptionBillingUnavailable.class, () -> repository.locked(fixture.account, () -> {
                repository.saveAgreement(original);
                return null;
            }));
            var closed = repository.agreement(fixture.account, fixture.subscription).orElseThrow();
            assertEquals("REMOTE_CANCELED", closed.terminalReason());
            assertEquals(SubscriptionState.CANCELED, closed.state());
            assertTrue(access.status(fixture.account).canInvoiceAt(fixture.now));
            assertFalse(access.status(fixture.account).canInvoiceAt(fixture.periodEnd));
        }
    }

    @Test
    void checkoutResolutionIsAccountScopedAndPreservesCompletedHistory() throws Exception {
        try (var fixture = new Fixture(); var other = new Fixture()) {
            var binding = repository.binding(fixture.account, fixture.attempt, fixture.session).orElseThrow();
            assertEquals(fixture.model, binding.priceModelId());
            assertEquals(fixture.price, binding.priceId());
            assertTrue(binding.catalogPricing());
            assertTrue(repository.binding(other.account, fixture.attempt, fixture.session).isEmpty());
            assertTrue(repository.binding(fixture.account, fixture.attempt, "cs_test_wrong").isEmpty());

            repository.locked(fixture.account, () -> {
                repository.resolveCheckout(fixture.account, fixture.attempt, fixture.subscription, fixture.now);
                repository.resolveCheckout(fixture.account, fixture.attempt, fixture.subscription, fixture.now.plusSeconds(60));
                return null;
            });
            assertEquals(1, count("SELECT COUNT(*) FROM subscription_checkout_attempts "
                    + "WHERE account_id=? AND status='COMPLETED' AND resolved_at IS NOT NULL", fixture.account));
            assertThrows(SubscriptionBillingUnavailable.class, () -> repository.locked(other.account, () -> {
                repository.resolveCheckout(other.account, fixture.attempt, fixture.subscription, fixture.now);
                return null;
            }));
            assertThrows(SubscriptionBillingUnavailable.class, () -> repository.locked(fixture.account, () -> {
                repository.resolveCheckout(fixture.account, fixture.attempt, "sub_wrong", fixture.now);
                return null;
            }));
            assertEquals(fixture.account, repository.accountForCustomer(fixture.customer).orElseThrow());
            assertTrue(repository.accountForCustomer("cus_unknown").isEmpty());
            assertTrue(repository.maintenanceCandidates(null, 1000).contains(fixture.account));
        }
    }

    @Test
    void futurePaidPeriodsDoNotGrantEarlyAccessAndOwnerGrantsRemainIndependent() throws Exception {
        try (var fixture = new Fixture()) {
            var invoice = BillingInvoice.builder().id(fixture.invoiceId).subscriptionId(fixture.subscription)
                    .customerId(fixture.customer).priceId(fixture.price).state(BillingInvoice.State.PAID)
                    .periodStart(fixture.now.plus(1, ChronoUnit.DAYS)).periodEnd(fixture.now.plus(31, ChronoUnit.DAYS))
                    .paidAt(fixture.now).amountPaid(4900).total(4900).build();
            repository.locked(fixture.account, () -> {
                repository.saveAgreement(fixture.agreement());
                repository.recordPaidPeriod(fixture.account, fixture.subscription, invoice);
                return null;
            });
            assertFalse(access.status(fixture.account).canInvoiceAt(fixture.now));
            assertTrue(repository.paidUntil(fixture.account, fixture.subscription).isEmpty());
            execute("INSERT INTO subscription_access_grants(id,account_id,kind,granted_by,reason) "
                    + "VALUES (?,?,'OWNER','test','independent owner grant')", UUID.randomUUID(), fixture.account);
            assertTrue(repository.ownerAccess(fixture.account));
            assertTrue(access.status(fixture.account).canInvoiceAt(fixture.now));
        }
    }

    @Test
    void maintenancePrioritizesUncheckedAccountsAndRetainsFailureProgress() throws Exception {
        try (var first = new Fixture(); var second = new Fixture(); var third = new Fixture()) {
            repository.markMaintenanceChecked(first.account, first.now.minusSeconds(120));
            repository.markMaintenanceChecked(second.account, second.now.minusSeconds(60));
            var candidates = repository.maintenanceCandidates(null, 1000);
            assertTrue(candidates.indexOf(third.account) < candidates.indexOf(first.account));
            assertTrue(candidates.indexOf(first.account) < candidates.indexOf(second.account));

            assertThrows(IllegalStateException.class, () -> repository.locked(third.account, () -> {
                repository.saveAgreement(third.agreement());
                throw new IllegalStateException("temporary Stripe failure");
            }));
            // Called outside the failed business transaction: a broken account cannot
            // starve every later account on each bounded Cloud Scheduler invocation.
            repository.markMaintenanceChecked(third.account, third.now);
            candidates = repository.maintenanceCandidates(null, 1000);
            assertTrue(candidates.indexOf(second.account) < candidates.indexOf(third.account));
            assertTrue(repository.agreement(third.account, third.subscription).isEmpty());
        }
    }

    @Test
    void parallelDeliveriesAreSerializedAndAlertsAreDeduplicated() throws Exception {
        try (var fixture = new Fixture()) {
            var start = new CountDownLatch(1);
            var effects = new AtomicInteger();
            var loader = Thread.currentThread().getContextClassLoader();
            var executor = Executors.newFixedThreadPool(2, work -> {
                var thread = new Thread(work);
                thread.setContextClassLoader(loader);
                return thread;
            });
            try {
                var operation = (java.util.concurrent.Callable<Void>) () -> {
                    start.await();
                    repository.locked(fixture.account, () -> {
                        if (repository.beginEvent(fixture.event())) {
                            effects.incrementAndGet();
                            repository.alert(fixture.account, fixture.eventId, "REFUND_REVIEW", fixture.subscription, fixture.now);
                            repository.alert(fixture.account, fixture.eventId, "REFUND_REVIEW", fixture.subscription, fixture.now);
                            repository.completeEvent(fixture.eventId, fixture.now);
                        }
                        return null;
                    });
                    return null;
                };
                var first = executor.submit(operation);
                var second = executor.submit(operation);
                start.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
                assertEquals(1, effects.get());
                assertEquals(1, count("SELECT COUNT(*) FROM subscription_alerts WHERE account_id=?", fixture.account));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final UUID account = UUID.randomUUID();
        final UUID model = UUID.randomUUID();
        final UUID agreement = UUID.randomUUID();
        final UUID attempt = UUID.randomUUID();
        final String customer = "cus_" + account;
        final String subscription = "sub_" + account;
        final String price = "price_" + model;
        final String session = "cs_test_" + attempt;
        final String invoiceId = "in_" + account;
        final String eventId = "evt_" + account;
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final Instant periodStart = now.minus(1, ChronoUnit.DAYS);
        final Instant periodEnd = now.plus(29, ChronoUnit.DAYS);

        Fixture() throws SQLException {
            execute("INSERT INTO accounts(id,email,password_hash,verified) VALUES (?,?,'test',true)", account, account + "@example.test");
            execute("INSERT INTO subscription_accounts(account_id,stripe_customer_id) VALUES (?,?)", account, customer);
            execute("INSERT INTO subscription_price_models(id,name,plan,amount,vat_percent,vat_inclusive,state,"
                    + "stripe_product_id,stripe_price_id,stripe_tax_rate_id,created_by,created_at) "
                    + "VALUES (?,'Lifecycle test','MONTHLY',49,25,false,'READY',?,?,?,?,?)", model,
                    "prod_" + model, price, "txr_" + model, account, now);
            execute("INSERT INTO subscription_checkout_attempts(id,account_id,plan,status,idempotency_key,"
                    + "stripe_session_id,expires_at,price_model_id,catalog_pricing) VALUES (?,?,'MONTHLY','OPEN',?,?,?,?,true)",
                    attempt, account, "checkout-" + attempt, session, now.plusSeconds(3600), model);
        }

        BillingEvent event() {
            return BillingEvent.builder().id(eventId).type("invoice.paid").objectId(invoiceId).createdAt(now).build();
        }

        SubscriptionAgreement agreement() {
            return SubscriptionAgreement.builder().id(agreement).accountId(account).subscriptionId(subscription)
                    .priceModelId(model).plan(SubscriptionPlan.MONTHLY).state(SubscriptionState.ACTIVE)
                    .periodStart(periodStart).periodEnd(periodEnd).updatedAt(now).build();
        }

        BillingInvoice invoice() {
            return BillingInvoice.builder().id(invoiceId).subscriptionId(subscription).customerId(customer).priceId(price)
                    .state(BillingInvoice.State.PAID).periodStart(periodStart).periodEnd(periodEnd)
                    .paidAt(now).amountPaid(4900).total(4900).build();
        }

        @Override
        public void close() throws SQLException {
            execute("DELETE FROM subscription_processed_events WHERE stripe_event_id=?", eventId);
            execute("DELETE FROM subscription_alerts WHERE account_id=?", account);
            execute("DELETE FROM subscription_paid_periods WHERE account_id=?", account);
            execute("DELETE FROM subscription_agreements WHERE account_id=?", account);
            execute("DELETE FROM subscription_checkout_attempts WHERE account_id=?", account);
            execute("DELETE FROM subscription_accounts WHERE account_id=?", account);
            execute("DELETE FROM subscription_access_grants WHERE account_id=?", account);
            execute("DELETE FROM subscription_price_models WHERE created_by=?", account);
            execute("DELETE FROM accounts WHERE id=?", account);
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1,
                    values[i] instanceof Instant value ? Timestamp.from(value) : values[i]);
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
