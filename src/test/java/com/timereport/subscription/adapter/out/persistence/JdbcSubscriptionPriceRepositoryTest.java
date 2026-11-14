package com.timereport.subscription.adapter.out.persistence;

import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.application.service.SubscriptionPurchaseConflict;
import com.timereport.subscription.domain.StripePriceReferences;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class JdbcSubscriptionPriceRepositoryTest {
    @Inject DataSource dataSource;
    @Inject JdbcSubscriptionPriceRepository repository;
    @Inject JdbcSubscriptionPurchaseRepository purchases;

    @Test
    void priceDefinitionsAreIdempotentImmutableAndActivationIsPerPlan() throws SQLException {
        UUID actor = account();
        UUID other = account();
        try {
            var monthly = draft(actor, SubscriptionPlan.MONTHLY);
            assertEquals(monthly, repository.reserve(monthly));
            assertEquals(monthly, repository.reserve(monthly.toBuilder().createdAt(Instant.now().plusSeconds(30)).build()));
            assertThrows(SubscriptionPurchaseConflict.class, () -> repository.reserve(monthly.toBuilder()
                    .amount(new BigDecimal("50")).build()));
            assertThrows(SubscriptionPurchaseConflict.class, () -> repository.reserve(monthly.toBuilder().createdBy(other).build()));
            assertThrows(SubscriptionPurchaseConflict.class, () -> repository.activate(monthly.id()));
            var refs = references();
            repository.complete(monthly.id(), refs);
            repository.complete(monthly.id(), refs);
            assertThrows(SubscriptionPurchaseConflict.class, () -> repository.complete(monthly.id(), references()));
            assertTrue(repository.activate(monthly.id()).active());

            var yearly = ready(actor, SubscriptionPlan.YEARLY);
            assertTrue(repository.activate(yearly.id()).active());
            assertTrue(repository.find(monthly.id()).orElseThrow().active());
            var replacement = ready(actor, SubscriptionPlan.MONTHLY);
            repository.activate(replacement.id());
            assertFalse(repository.find(monthly.id()).orElseThrow().active());
            assertEquals(refs, repository.find(monthly.id()).orElseThrow().stripe());
            assertTrue(repository.find(replacement.id()).orElseThrow().active());
            assertTrue(repository.find(yearly.id()).orElseThrow().active());
            assertEquals(3, repository.list().stream().filter(model -> model.createdBy().equals(actor)).count());
        } finally {
            cleanup(actor);
            cleanup(other);
        }
    }

    @Test
    void checkoutBindsOnceAndRetainsItsPriceWhenTheActiveSelectionChanges() throws SQLException {
        UUID actor = account();
        UUID firstBuyer = account();
        UUID secondBuyer = account();
        try {
            var original = ready(actor, SubscriptionPlan.MONTHLY);
            repository.activate(original.id());
            UUID first = purchases.reserve(firstBuyer, SubscriptionPlan.MONTHLY, Instant.now()).id();
            assertEquals(original.id(), repository.forCheckout(firstBuyer, first, SubscriptionPlan.MONTHLY).id());

            var replacement = ready(actor, SubscriptionPlan.MONTHLY);
            repository.activate(replacement.id());
            var bound = repository.forCheckout(firstBuyer, first, SubscriptionPlan.MONTHLY);
            assertEquals(original.id(), bound.id());
            assertFalse(bound.active());
            UUID second = purchases.reserve(secondBuyer, SubscriptionPlan.MONTHLY, Instant.now()).id();
            assertEquals(replacement.id(), repository.forCheckout(secondBuyer, second, SubscriptionPlan.MONTHLY).id());
            assertThrows(SubscriptionPurchaseConflict.class,
                    () -> repository.forCheckout(actor, first, SubscriptionPlan.MONTHLY));
            assertThrows(SubscriptionPurchaseConflict.class,
                    () -> repository.forCheckout(firstBuyer, first, SubscriptionPlan.YEARLY));
        } finally {
            cleanup(firstBuyer);
            cleanup(secondBuyer);
            cleanup(actor);
        }
    }

    @Test
    void missingSelectionAndLegacyAttemptsNeverFallBackToAnUnrelatedPrice() throws SQLException {
        UUID buyer = account();
        UUID legacyBuyer = account();
        UUID previousSelection;
        try (var connection = dataSource.getConnection(); var select = connection.prepareStatement(
                "SELECT price_model_id FROM subscription_price_selections WHERE plan='YEARLY'")) {
            try (var rows = select.executeQuery()) {
                rows.next();
                previousSelection = rows.getObject(1, UUID.class);
            }
        }
        try {
            execute("UPDATE subscription_price_selections SET price_model_id=NULL WHERE plan='YEARLY'");
            UUID fresh = attempt(buyer, SubscriptionPlan.YEARLY, true);
            var unavailable = assertThrows(SubscriptionBillingUnavailable.class,
                    () -> repository.forCheckout(buyer, fresh, SubscriptionPlan.YEARLY));
            assertTrue(unavailable.getMessage().contains("årspris"));
            UUID legacy = attempt(legacyBuyer, SubscriptionPlan.YEARLY, false);
            assertThrows(SubscriptionPurchaseConflict.class,
                    () -> repository.forCheckout(legacyBuyer, legacy, SubscriptionPlan.YEARLY));
        } finally {
            execute("UPDATE subscription_price_selections SET price_model_id=? WHERE plan='YEARLY'", previousSelection);
            cleanup(buyer);
            cleanup(legacyBuyer);
        }
    }

    private UUID account() throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO accounts(id,email,password_hash,verified) VALUES (?,?,'test',true)", id, id + "@example.test");
        return id;
    }

    private UUID attempt(UUID accountId, SubscriptionPlan plan, boolean catalog) throws SQLException {
        UUID id = UUID.randomUUID();
        if (catalog) {
            execute("INSERT INTO subscription_checkout_attempts(id,account_id,plan,status,idempotency_key,catalog_pricing) "
                    + "VALUES (?,?,?,'CREATING',?,true)", id, accountId, plan.name(), "test-" + id);
        } else {
            // Simulate an old application revision inserting after the migration.
            execute("INSERT INTO subscription_checkout_attempts(id,account_id,plan,status,idempotency_key) "
                    + "VALUES (?,?,?,'CREATING',?)", id, accountId, plan.name(), "test-" + id);
        }
        return id;
    }

    private SubscriptionPriceModel ready(UUID actor, SubscriptionPlan plan) {
        var draft = repository.reserve(draft(actor, plan));
        repository.complete(draft.id(), references());
        return repository.find(draft.id()).orElseThrow();
    }

    private SubscriptionPriceModel draft(UUID actor, SubscriptionPlan plan) {
        return SubscriptionPriceModel.builder().id(UUID.randomUUID()).name("Standard " + plan).plan(plan)
                .amount(new BigDecimal("49")).vatPercent(new BigDecimal("25")).vatInclusive(false)
                .state(SubscriptionPriceModel.State.CREATING).createdBy(actor)
                .createdAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)).build();
    }

    private StripePriceReferences references() {
        String suffix = UUID.randomUUID().toString();
        return new StripePriceReferences("prod_" + suffix, "price_" + suffix, "txr_" + suffix);
    }

    private void cleanup(UUID accountId) throws SQLException {
        execute("DELETE FROM subscription_checkout_attempts WHERE account_id=?", accountId);
        execute("DELETE FROM subscription_accounts WHERE account_id=?", accountId);
        execute("UPDATE subscription_price_selections SET price_model_id=NULL WHERE price_model_id IN "
                + "(SELECT id FROM subscription_price_models WHERE created_by=?)", accountId);
        execute("DELETE FROM subscription_price_models WHERE created_by=?", accountId);
        execute("DELETE FROM accounts WHERE id=?", accountId);
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
}
