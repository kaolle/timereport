package com.timereport.invoicing.adapter.in.rest;

import com.timereport.authentication.AuthTestSupport;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(InvoicingSubscriptionGateHttpTest.PaidGateProfile.class)
class InvoicingSubscriptionGateHttpTest {
    public static class PaidGateProfile implements QuarkusTestProfile {
        public Map<String, String> getConfigOverrides() {
            return Map.of("subscription.invoicing-owner-only", "false",
                    "subscription.enforce-invoice-access", "true");
        }
    }

    @Inject AuthTestSupport auth;
    @Inject DataSource dataSource;

    @Test
    void unpaidAccountCannotReadOrWriteAnyInvoicingDataButCanStillBuyAndRegisterTime() {
        var token = auth.login().accessToken();
        given().auth().oauth2(token).get("/api/subscription").then().statusCode(200)
                .body("entitled", equalTo(false), "invoicingAvailable", equalTo(false),
                        "invoicingBlockReason", equalTo("SUBSCRIPTION_REQUIRED"));
        for (String path : new String[]{"profile", "customers", "invoices", "draft"})
            given().auth().oauth2(token).get("/api/invoicing/" + path).then().statusCode(403)
                    .body("code", equalTo("SUBSCRIPTION_REQUIRED"));
        for (String path : new String[]{"draft/pdf", "invoices", "invoices/1061/email"})
            given().auth().oauth2(token).contentType("application/json").body("{}")
                    .post("/api/invoicing/" + path).then().statusCode(403)
                    .body("code", equalTo("SUBSCRIPTION_REQUIRED"));
        given().auth().oauth2(token).get("/api/invoicing/customer-code-labels").then().statusCode(200);
        given().auth().oauth2(token).queryParam("from", "2026-11-01").queryParam("to", "2026-11-30")
                .get("/api/time-entries").then().statusCode(200);
        // The disabled Stripe adapter may reject setup, but invoicing access must not block buying.
        given().auth().oauth2(token).contentType("application/json").body("{\"plan\":\"MONTHLY\"}")
                .post("/api/subscription/checkout").then().statusCode(503)
                .body("code", equalTo("BILLING_UNAVAILABLE"));
    }

    @Test
    void paidPeriodUnlocksOnlyItsAccountAndExpiryClosesItAgain() throws Exception {
        var buyer = auth.login();
        var other = auth.login();
        UUID accountId = UUID.fromString(buyer.accountId());
        String subscription = "sub_gate_" + UUID.randomUUID();
        var now = Instant.now();
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("INSERT INTO subscription_agreements "
                    + "(id,account_id,stripe_subscription_id,plan,status) VALUES (?,?,?,'MONTHLY','ACTIVE')")) {
                statement.setObject(1, UUID.randomUUID()); statement.setObject(2, accountId);
                statement.setString(3, subscription); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO subscription_paid_periods "
                    + "(id,account_id,stripe_invoice_id,stripe_subscription_id,period_start,period_end) VALUES (?,?,?,?,?,?)")) {
                statement.setObject(1, UUID.randomUUID()); statement.setObject(2, accountId);
                statement.setString(3, "in_gate_" + UUID.randomUUID()); statement.setString(4, subscription);
                statement.setTimestamp(5, Timestamp.from(now.minus(2, ChronoUnit.DAYS)));
                statement.setTimestamp(6, Timestamp.from(now.plus(30, ChronoUnit.DAYS))); statement.executeUpdate();
            }
        }
        given().auth().oauth2(buyer.accessToken()).get("/api/subscription").then().statusCode(200)
                .body("entitled", equalTo(true), "invoicingAvailable", equalTo(true));
        given().auth().oauth2(buyer.accessToken()).get("/api/invoicing/profile").then().statusCode(204);
        given().auth().oauth2(other.accessToken()).get("/api/invoicing/profile").then().statusCode(403);
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE subscription_paid_periods SET period_end=? WHERE account_id=?")) {
            statement.setTimestamp(1, Timestamp.from(now.minus(1, ChronoUnit.DAYS)));
            statement.setObject(2, accountId); statement.executeUpdate();
        }
        given().auth().oauth2(buyer.accessToken()).get("/api/invoicing/profile").then().statusCode(403)
                .body("code", equalTo("SUBSCRIPTION_REQUIRED"));
    }
}
