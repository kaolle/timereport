package com.timereport.subscription.adapter.out.persistence;

import com.timereport.subscription.application.port.out.SubscriptionRepository;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionSnapshot;
import com.timereport.subscription.domain.SubscriptionState;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

@ApplicationScoped
public class JdbcSubscriptionRepository implements SubscriptionRepository {
    private static final String STATUS_SQL = """
            SELECT s.plan, COALESCE(s.status, 'NONE') AS status, s.grace_until,
                   s.cancel_at_period_end,
                   (SELECT MAX(p.period_end) FROM subscription_paid_periods p
                    WHERE p.account_id = requested.account_id AND p.voided_at IS NULL) AS paid_until,
                   EXISTS (SELECT 1 FROM subscription_access_grants g
                           WHERE g.account_id = requested.account_id AND g.kind = 'OWNER'
                             AND g.revoked_at IS NULL) AS owner_access
            FROM (SELECT ?::uuid AS account_id) requested
            LEFT JOIN LATERAL (
                SELECT plan, status, grace_until, cancel_at_period_end
                FROM subscription_agreements
                WHERE account_id = requested.account_id
                ORDER BY created_at DESC, id DESC
                LIMIT 1
            ) s ON true
            """;

    private final DataSource dataSource;

    public JdbcSubscriptionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public SubscriptionSnapshot status(UUID accountId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(STATUS_SQL)) {
            statement.setObject(1, accountId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Abonnemangsstatus saknas.");
                var plan = rows.getString("plan");
                var grace = rows.getTimestamp("grace_until");
                var paid = rows.getTimestamp("paid_until");
                return SubscriptionSnapshot.builder().accountId(accountId)
                        .plan(plan == null ? null : SubscriptionPlan.valueOf(plan))
                        .state(SubscriptionState.valueOf(rows.getString("status")))
                        .paidUntil(paid == null ? null : paid.toInstant())
                        .graceUntil(grace == null ? null : grace.toInstant())
                        .cancelAtPeriodEnd(rows.getBoolean("cancel_at_period_end"))
                        .ownerAccess(rows.getBoolean("owner_access")).build();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Kunde inte läsa abonnemangsstatus.", e);
        }
    }
}
