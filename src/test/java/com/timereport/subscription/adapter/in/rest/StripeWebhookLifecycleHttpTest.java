package com.timereport.subscription.adapter.in.rest;

import com.timereport.authentication.AuthTestSupport;
import com.timereport.subscription.application.port.out.SubscriptionLifecycleGateway;
import com.timereport.subscription.domain.BillingEventLink;
import com.timereport.subscription.domain.BillingInvoice;
import com.timereport.subscription.domain.BillingSubscription;
import com.timereport.subscription.domain.SubscriptionPlan;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.timereport.subscription.adapter.in.rest.StripeWebhookVerifierTest.SECRET;
import static com.timereport.subscription.adapter.in.rest.StripeWebhookVerifierTest.sign;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** Exercises the signed public endpoint with real use cases, catalog checks and PostgreSQL writes. */
@QuarkusTest
@TestProfile(StripeWebhookLifecycleHttpTest.Profile.class)
class StripeWebhookLifecycleHttpTest {
    private static final String PATH = "/api/subscription/webhooks/stripe";
    @Inject DataSource dataSource;
    @Inject AuthTestSupport auth;
    @InjectMock SubscriptionLifecycleGateway gateway;
    private final long eventCreated = Instant.now().getEpochSecond();

    @BeforeEach
    void resetGateway() {
        reset(gateway);
    }

    @Test
    void paidWebhookGrantsOnePeriodAndCancellationRetainsThatPaidAccess() throws Exception {
        var fixture = new Fixture();
        when(gateway.locate(any())).thenReturn(new BillingEventLink(fixture.customer, fixture.subscription, null));
        when(gateway.fetch(fixture.subscription)).thenReturn(fixture.remote(false, fixture.account,
                fixture.attempt, fixture.session, fixture.customer));
        String event = "evt_" + UUID.randomUUID();

        post(event, "invoice.paid", fixture.invoiceId).then().statusCode(200).body("status", equalTo("PROCESSED"));
        given().auth().oauth2(fixture.login.accessToken()).get("/api/subscription").then().statusCode(200)
                .body("status", equalTo("ACTIVE"), "entitled", equalTo(true), "ownerAccess", equalTo(false),
                        "paidUntil", equalTo(fixture.end.toString()));
        post(event, "invoice.paid", fixture.invoiceId).then().statusCode(200).body("status", equalTo("DUPLICATE"));
        assertEquals(1, count("SELECT COUNT(*) FROM subscription_paid_periods WHERE account_id=?", fixture.account));
        assertEquals(1, count("SELECT COUNT(*) FROM subscription_checkout_attempts "
                + "WHERE account_id=? AND status='COMPLETED' AND resolved_at IS NOT NULL", fixture.account));

        when(gateway.fetch(fixture.subscription)).thenReturn(fixture.remote(true, fixture.account,
                fixture.attempt, fixture.session, fixture.customer));
        post("evt_" + UUID.randomUUID(), "customer.subscription.deleted", fixture.subscription)
                .then().statusCode(200).body("status", equalTo("PROCESSED"));
        given().auth().oauth2(fixture.login.accessToken()).get("/api/subscription").then().statusCode(200)
                .body("status", equalTo("CANCELED"), "entitled", equalTo(true), "paidUntil", equalTo(fixture.end.toString()));
        assertEquals(1, count("SELECT COUNT(*) FROM subscription_paid_periods WHERE account_id=?", fixture.account));
    }

    @Test
    void signedPaymentCannotUseAnotherAccountsMetadataCheckoutOrCustomer() throws Exception {
        var first = new Fixture();
        var second = new Fixture();
        when(gateway.locate(any())).thenReturn(new BillingEventLink(first.customer, first.subscription, null));
        when(gateway.fetch(first.subscription)).thenReturn(first.remote(false, second.account,
                second.attempt, second.session, first.customer));

        post("evt_" + UUID.randomUUID(), "invoice.paid", first.invoiceId).then().statusCode(200)
                .body("status", equalTo("IGNORED"));
        when(gateway.fetch(first.subscription)).thenReturn(first.remote(false, first.account,
                second.attempt, second.session, first.customer));
        post("evt_" + UUID.randomUUID(), "invoice.paid", first.invoiceId).then().statusCode(200)
                .body("status", equalTo("IGNORED"));

        String mismatchedCustomerEvent = "evt_" + UUID.randomUUID();
        when(gateway.fetch(first.subscription)).thenReturn(first.remote(false, first.account,
                first.attempt, first.session, second.customer));
        post(mismatchedCustomerEvent, "invoice.paid", first.invoiceId).then().statusCode(503);
        assertEquals(0, count("SELECT COUNT(*) FROM subscription_processed_events WHERE stripe_event_id=?", mismatchedCustomerEvent));
        assertEquals(0, count("SELECT COUNT(*) FROM subscription_paid_periods WHERE account_id=?", first.account));
        assertEquals(0, count("SELECT COUNT(*) FROM subscription_paid_periods WHERE account_id=?", second.account));
        for (var fixture : List.of(first, second))
            given().auth().oauth2(fixture.login.accessToken()).get("/api/subscription").then().statusCode(200)
                    .body("entitled", equalTo(false));
    }

    private io.restassured.response.Response post(String eventId, String type, String objectId) {
        long now = Instant.now().getEpochSecond();
        String objectType = type.startsWith("invoice.") ? "invoice" : "subscription";
        String body = "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"created\":" + eventCreated
                + ",\"livemode\":false,\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\""
                + objectId + "\",\"object\":\"" + objectType + "\"}}}";
        return given().contentType(ContentType.JSON).body(body).header("Stripe-Signature", sign(body, now)).post(PATH);
    }

    private final class Fixture {
        final AuthTestSupport.Login login = auth.login();
        final UUID account = UUID.fromString(login.accountId());
        final UUID model = UUID.randomUUID();
        final UUID attempt = UUID.randomUUID();
        final String customer = "cus_" + account;
        final String subscription = "sub_" + account;
        final String price = "price_" + model;
        final String session = "cs_test_" + attempt;
        final String invoiceId = "in_" + account;
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final Instant start = now.minus(1, ChronoUnit.DAYS);
        final Instant end = now.plus(29, ChronoUnit.DAYS);

        Fixture() throws SQLException {
            execute("INSERT INTO subscription_accounts(account_id,stripe_customer_id) VALUES (?,?)", account, customer);
            execute("INSERT INTO subscription_price_models(id,name,plan,amount,vat_percent,vat_inclusive,state,"
                    + "stripe_product_id,stripe_price_id,stripe_tax_rate_id,created_by,created_at) "
                    + "VALUES (?,'HTTP lifecycle test','MONTHLY',49,25,false,'READY',?,?,?,?,?)", model,
                    "prod_" + model, price, "txr_" + model, account, now);
            execute("INSERT INTO subscription_checkout_attempts(id,account_id,plan,status,idempotency_key,"
                    + "stripe_session_id,expires_at,price_model_id,catalog_pricing) VALUES (?,?,'MONTHLY','OPEN',?,?,?,?,true)",
                    attempt, account, "checkout-" + attempt, session, now.plusSeconds(3600), model);
        }

        BillingSubscription remote(boolean canceled, UUID claimedAccount, UUID claimedAttempt,
                                   String claimedSession, String claimedCustomer) {
            var paid = BillingInvoice.builder().id(invoiceId).subscriptionId(subscription).customerId(claimedCustomer)
                    .priceId(price).state(BillingInvoice.State.PAID).periodStart(start).periodEnd(end)
                    .paidAt(now.minusSeconds(60)).amountPaid(6125).total(6125).build();
            return BillingSubscription.builder().id(subscription).customerId(claimedCustomer).accountId(claimedAccount)
                    .priceModelId(model).checkoutAttemptId(claimedAttempt).checkoutSessionId(claimedSession)
                    .priceId(price).plan(SubscriptionPlan.MONTHLY)
                    .state(canceled ? BillingSubscription.State.CANCELED : BillingSubscription.State.ACTIVE)
                    .periodStart(start).periodEnd(end).endedAt(canceled ? now : null).invoices(List.of(paid)).build();
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1,
                    values[index] instanceof Instant value ? Timestamp.from(value) : values[index]);
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

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("subscription.webhooks-enabled", "true", "subscription.stripe.webhook-secret", SECRET);
        }
    }
}
