package com.timereport.invoicing.adapter.in.rest;

import com.timereport.authentication.AuthTestSupport;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(InvoicingRolloutHttpTest.OwnerOnlyProfile.class)
class InvoicingRolloutHttpTest {
    public static class OwnerOnlyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("subscription.invoicing-owner-only", "true");
        }
    }

    @Inject AuthTestSupport auth;
    @Inject DataSource dataSource;

    @Test
    void nonOwnerCanRegisterTimeButCannotAccessInvoicing() {
        var login = auth.login();
        given().auth().oauth2(login.accessToken()).get("/api/subscription").then().statusCode(200)
                .body("invoicingAvailable", equalTo(false));
        given().auth().oauth2(login.accessToken()).get("/api/invoicing/profile").then().statusCode(403)
                .body("code", equalTo("INVOICING_COMING_SOON"));
        given().auth().oauth2(login.accessToken()).get("/api/invoicing/invoices").then().statusCode(403);
        given().auth().oauth2(login.accessToken()).contentType("application/json")
                .body(Map.of("plan", "MONTHLY")).post("/api/subscription/checkout")
                .then().statusCode(403).body("code", equalTo("INVOICING_COMING_SOON"));
        given().auth().oauth2(login.accessToken()).post("/api/subscription/portal").then().statusCode(403);
        given().auth().oauth2(login.accessToken()).get("/api/invoicing/customer-code-labels").then().statusCode(200);
        given().auth().oauth2(login.accessToken()).queryParam("from", "2026-11-01")
                .queryParam("to", "2026-11-30").get("/api/time-entries").then().statusCode(200);
    }

    @Test
    void activeOwnerGrantUnlocksInvoicing() throws SQLException {
        var login = auth.login();
        UUID accountId = UUID.fromString(login.accountId());
        try {
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                    "INSERT INTO subscription_access_grants (id, account_id, kind, granted_by, reason) "
                            + "VALUES (?, ?, 'OWNER', 'test', 'rollout test')")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, accountId);
                statement.executeUpdate();
            }
            given().auth().oauth2(login.accessToken()).get("/api/subscription").then().statusCode(200)
                    .body("invoicingAvailable", equalTo(true));
            given().auth().oauth2(login.accessToken()).get("/api/invoicing/profile").then().statusCode(204);
            given().auth().oauth2(login.accessToken()).contentType("application/json")
                    .body(Map.of("plan", "MONTHLY")).post("/api/subscription/checkout")
                    .then().statusCode(503).body("code", equalTo("BILLING_UNAVAILABLE"));
            given().auth().oauth2(login.accessToken()).post("/api/subscription/portal")
                    .then().statusCode(503).body("code", equalTo("BILLING_UNAVAILABLE"));
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                    "DELETE FROM subscription_access_grants WHERE account_id = ?")) {
                statement.setObject(1, accountId);
                statement.executeUpdate();
            }
        }
    }
}
