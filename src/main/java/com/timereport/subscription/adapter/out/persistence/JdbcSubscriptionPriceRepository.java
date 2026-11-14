package com.timereport.subscription.adapter.out.persistence;

import com.timereport.subscription.application.port.out.SubscriptionPriceRepository;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.application.service.SubscriptionPriceNotFound;
import com.timereport.subscription.application.service.SubscriptionPurchaseConflict;
import com.timereport.subscription.domain.StripePriceReferences;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JdbcSubscriptionPriceRepository implements SubscriptionPriceRepository {
    private static final String SELECT_PRICE = "SELECT p.*, (s.price_model_id IS NOT NULL) AS active "
            + "FROM subscription_price_models p LEFT JOIN subscription_price_selections s ON s.price_model_id = p.id ";
    private final DataSource dataSource;

    public JdbcSubscriptionPriceRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<SubscriptionPriceModel> list() {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                SELECT_PRICE + "ORDER BY p.created_at DESC, p.id")) {
            var models = new ArrayList<SubscriptionPriceModel>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) models.add(read(rows));
            }
            return List.copyOf(models);
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SubscriptionPriceModel reserve(SubscriptionPriceModel draft) {
        if (draft.state() != SubscriptionPriceModel.State.CREATING || draft.active())
            throw new IllegalArgumentException("En ny prismodell måste vara ett opublicerat utkast.");
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("INSERT INTO subscription_price_models "
                    + "(id, name, plan, amount, vat_percent, vat_inclusive, state, created_by, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'CREATING', ?, ?) ON CONFLICT(id) DO NOTHING")) {
                statement.setObject(1, draft.id());
                statement.setString(2, draft.name());
                statement.setString(3, draft.plan().name());
                statement.setBigDecimal(4, draft.amount());
                statement.setBigDecimal(5, draft.vatPercent());
                statement.setBoolean(6, draft.vatInclusive());
                statement.setObject(7, draft.createdBy());
                statement.setTimestamp(8, Timestamp.from(draft.createdAt()));
                statement.executeUpdate();
            }
            var existing = find(connection, draft.id(), true).orElseThrow(SubscriptionPriceNotFound::new);
            if (!existing.createdBy().equals(draft.createdBy()) || !existing.name().equals(draft.name())
                    || existing.plan() != draft.plan() || !existing.amount().equals(draft.amount())
                    || !existing.vatPercent().equals(draft.vatPercent()) || existing.vatInclusive() != draft.vatInclusive())
                throw new SubscriptionPurchaseConflict("Prisets id används redan för en annan prismodell. Skapa en ny prismodell.");
            return existing;
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void complete(UUID id, StripePriceReferences references) {
        try (var connection = dataSource.getConnection()) {
            var existing = find(connection, id, true).orElseThrow(SubscriptionPriceNotFound::new);
            if (existing.state() == SubscriptionPriceModel.State.READY) {
                if (!existing.stripe().equals(references))
                    throw new SubscriptionPurchaseConflict("Prismodellen har redan andra Stripe-referenser.");
                return;
            }
            try (var statement = connection.prepareStatement("UPDATE subscription_price_models "
                    + "SET state='READY', stripe_product_id=?, stripe_price_id=?, stripe_tax_rate_id=? WHERE id=?")) {
                statement.setString(1, references.productId());
                statement.setString(2, references.priceId());
                statement.setString(3, references.taxRateId());
                statement.setObject(4, id);
                statement.executeUpdate();
            }
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SubscriptionPriceModel activate(UUID id) {
        try (var connection = dataSource.getConnection()) {
            var model = find(connection, id, false).orElseThrow(SubscriptionPriceNotFound::new);
            if (model.state() != SubscriptionPriceModel.State.READY)
                throw new SubscriptionPurchaseConflict("Vänta tills priset har skapats i Stripe innan det aktiveras.");
            try (var lock = connection.prepareStatement("SELECT plan FROM subscription_price_selections WHERE plan=? FOR UPDATE")) {
                lock.setString(1, model.plan().name());
                try (var rows = lock.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Prisplanen saknas.");
                }
            }
            try (var statement = connection.prepareStatement("UPDATE subscription_price_selections "
                    + "SET price_model_id=?, activated_at=now() WHERE plan=?")) {
                statement.setObject(1, id);
                statement.setString(2, model.plan().name());
                statement.executeUpdate();
            }
            return find(connection, id, false).orElseThrow(SubscriptionPriceNotFound::new);
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    public Optional<SubscriptionPriceModel> find(UUID id) {
        try (var connection = dataSource.getConnection()) {
            return find(connection, id, false);
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SubscriptionPriceModel forCheckout(UUID accountId, UUID attemptId, SubscriptionPlan plan) {
        try (var connection = dataSource.getConnection()) {
            UUID boundPrice;
            boolean catalogPricing;
            try (var lock = connection.prepareStatement("SELECT plan, status, price_model_id, catalog_pricing "
                    + "FROM subscription_checkout_attempts WHERE account_id=? AND id=? FOR UPDATE")) {
                lock.setObject(1, accountId);
                lock.setObject(2, attemptId);
                try (var rows = lock.executeQuery()) {
                    if (!rows.next() || !plan.name().equals(rows.getString("plan"))
                            || !"CREATING".equals(rows.getString("status")))
                        throw new SubscriptionPurchaseConflict("Köpsessionen är inte redo för ett nytt Stripe-anrop.");
                    boundPrice = rows.getObject("price_model_id", UUID.class);
                    catalogPricing = rows.getBoolean("catalog_pricing");
                }
            }
            if (boundPrice != null) return readyForPlan(connection, boundPrice, plan);
            if (!catalogPricing)
                throw new SubscriptionPurchaseConflict("Det här köpet använder den tidigare priskonfigurationen. Låt köpsessionen löpa ut innan du försöker igen.");
            UUID selected;
            // Serialize binding with activation so each reservation sees one
            // complete selection, while already-bound purchases retain theirs.
            try (var selection = connection.prepareStatement(
                    "SELECT price_model_id FROM subscription_price_selections WHERE plan=? FOR SHARE")) {
                selection.setString(1, plan.name());
                try (var rows = selection.executeQuery()) {
                    selected = rows.next() ? rows.getObject("price_model_id", UUID.class) : null;
                }
            }
            if (selected == null) {
                String interval = plan == SubscriptionPlan.MONTHLY ? "månadspris" : "årspris";
                throw new SubscriptionBillingUnavailable("Inget aktivt " + interval
                        + " är valt. Be en administratör skapa och aktivera ett abonnemangspris.");
            }
            var model = readyForPlan(connection, selected, plan);
            try (var statement = connection.prepareStatement("UPDATE subscription_checkout_attempts "
                    + "SET price_model_id=?, updated_at=now() WHERE account_id=? AND id=?")) {
                statement.setObject(1, selected);
                statement.setObject(2, accountId);
                statement.setObject(3, attemptId);
                statement.executeUpdate();
            }
            return model;
        } catch (SQLException failure) {
            throw databaseError(failure);
        }
    }

    private SubscriptionPriceModel readyForPlan(Connection connection, UUID id, SubscriptionPlan plan) throws SQLException {
        var model = find(connection, id, false).orElseThrow(SubscriptionPriceNotFound::new);
        if (model.state() != SubscriptionPriceModel.State.READY || model.plan() != plan)
            throw new SubscriptionPurchaseConflict("Prismodellen kan inte användas för det här köpet.");
        return model;
    }

    private Optional<SubscriptionPriceModel> find(Connection connection, UUID id, boolean lock) throws SQLException {
        try (var statement = connection.prepareStatement(SELECT_PRICE + "WHERE p.id=?" + (lock ? " FOR UPDATE OF p" : ""))) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    private SubscriptionPriceModel read(ResultSet row) throws SQLException {
        var state = SubscriptionPriceModel.State.valueOf(row.getString("state"));
        var references = state == SubscriptionPriceModel.State.READY ? StripePriceReferences.builder()
                .productId(row.getString("stripe_product_id")).priceId(row.getString("stripe_price_id"))
                .taxRateId(row.getString("stripe_tax_rate_id")).build() : null;
        return SubscriptionPriceModel.builder().id(row.getObject("id", UUID.class)).name(row.getString("name"))
                .plan(SubscriptionPlan.valueOf(row.getString("plan"))).amount(row.getBigDecimal("amount"))
                .vatPercent(row.getBigDecimal("vat_percent")).vatInclusive(row.getBoolean("vat_inclusive"))
                .state(state).stripe(references).active(row.getBoolean("active"))
                .createdBy(row.getObject("created_by", UUID.class)).createdAt(row.getTimestamp("created_at").toInstant()).build();
    }

    private IllegalStateException databaseError(SQLException failure) {
        return new IllegalStateException("Prismodellens databasåtgärd misslyckades.", failure);
    }
}
