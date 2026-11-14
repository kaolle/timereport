package com.timereport.subscription.adapter.in.rest;

import com.timereport.authentication.AuthTestSupport;
import com.timereport.subscription.application.port.out.SubscriptionPriceProvisioner;
import com.timereport.subscription.domain.StripePriceReferences;
import com.timereport.subscription.domain.SubscriptionPriceModel;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class SubscriptionPriceAdminResourceTest {
    private static final String PATH = "/api/admin/subscription/prices";
    @Inject AuthTestSupport auth;
    @Inject DataSource dataSource;
    @InjectMock SubscriptionPriceProvisioner stripe;

    @BeforeEach
    void provisionOnlyFakeStripeObjects() {
        when(stripe.provision(any())).thenAnswer(invocation -> {
            SubscriptionPriceModel model = invocation.getArgument(0);
            return new StripePriceReferences("prod_" + model.id(), "price_" + model.id(), "txr_" + model.id());
        });
    }

    @Test
    void administrativeRoleIsRequiredAndRevocationStopsAnExistingAdminJwt() throws SQLException {
        given().get(PATH).then().statusCode(401);
        var ordinary = auth.login();
        UUID accountId = UUID.fromString(ordinary.accountId());
        try {
            given().auth().oauth2(ordinary.accessToken()).get(PATH).then().statusCode(403);
            given().auth().oauth2(ordinary.accessToken()).contentType("application/json")
                    .body(request(UUID.randomUUID())).post(PATH).then().statusCode(403);
            execute("INSERT INTO account_roles(account_id,role) VALUES (?,'admin')", accountId);
            // A DB grant cannot make an old JWT claim administrative authority.
            given().auth().oauth2(ordinary.accessToken()).get(PATH).then().statusCode(403);
            var admin = auth.login(ordinary.email(), AuthTestSupport.PASSWORD);
            given().auth().oauth2(admin.accessToken()).get(PATH).then().statusCode(200)
                    .header("Cache-Control", "no-store");
            execute("DELETE FROM account_roles WHERE account_id=?", accountId);
            given().auth().oauth2(admin.accessToken()).get(PATH).then().statusCode(403);
            given().auth().oauth2(admin.accessToken()).contentType("application/json")
                    .body(request(UUID.randomUUID())).post(PATH).then().statusCode(403);
            verifyNoInteractions(stripe);
        } finally {
            execute("DELETE FROM account_roles WHERE account_id=?", accountId);
        }
    }

    @Test
    void adminCreatesRetriesAndSelectsPricesWithoutExposingCredentials() throws SQLException {
        var login = auth.login();
        UUID actor = UUID.fromString(login.accountId());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try {
            execute("INSERT INTO account_roles(account_id,role) VALUES (?,'admin')", actor);
            String token = auth.login(login.email(), AuthTestSupport.PASSWORD).accessToken();
            var request = request(first);
            for (int retry = 0; retry < 2; retry++) {
                given().auth().oauth2(token).contentType("application/json").body(request).post(PATH)
                        .then().statusCode(200).header("Cache-Control", "no-store")
                        .body("id", equalTo(first.toString()), "state", equalTo("READY"), "active", equalTo(false),
                                "amount", equalTo(49.0f), "vatPercent", equalTo(25.0f),
                                "stripePriceId", equalTo("price_" + first), "secretKey", nullValue());
            }
            verify(stripe, times(1)).provision(any());
            var changed = new HashMap<>(request);
            changed.put("amount", 50);
            given().auth().oauth2(token).contentType("application/json").body(changed).post(PATH)
                    .then().statusCode(409).body("code", equalTo("SUBSCRIPTION_CONFLICT"));
            given().auth().oauth2(token).post(PATH + "/" + first + "/activate").then().statusCode(200)
                    .body("active", equalTo(true));
            given().auth().oauth2(token).contentType("application/json").body(request(second)).post(PATH)
                    .then().statusCode(200).body("active", equalTo(false));
            given().auth().oauth2(token).post(PATH + "/" + second + "/activate").then().statusCode(200)
                    .body("active", equalTo(true));
            given().auth().oauth2(token).get(PATH).then().statusCode(200)
                    .body("find { it.id == '" + first + "' }.active", equalTo(false),
                            "find { it.id == '" + second + "' }.active", equalTo(true));
            given().auth().oauth2(token).post(PATH + "/" + UUID.randomUUID() + "/activate")
                    .then().statusCode(404).body("code", equalTo("PRICE_NOT_FOUND"));
        } finally {
            execute("UPDATE subscription_price_selections SET price_model_id=NULL WHERE price_model_id IN "
                    + "(SELECT id FROM subscription_price_models WHERE created_by=?)", actor);
            execute("DELETE FROM subscription_price_models WHERE created_by=?", actor);
            execute("DELETE FROM account_roles WHERE account_id=?", actor);
        }
    }

    @Test
    void invalidAmountsAndRatesAreRejectedBeforeProvisioning() throws SQLException {
        var login = auth.login();
        UUID actor = UUID.fromString(login.accountId());
        try {
            execute("INSERT INTO account_roles(account_id,role) VALUES (?,'admin')", actor);
            String token = auth.login(login.email(), AuthTestSupport.PASSWORD).accessToken();
            for (var field : new String[]{"amount", "vatPercent"}) {
                var invalid = new HashMap<>(request(UUID.randomUUID()));
                invalid.put(field, field.equals("amount") ? 0 : 101);
                given().auth().oauth2(token).contentType("application/json").body(invalid).post(PATH)
                        .then().statusCode(400);
            }
            var tooPrecise = new HashMap<>(request(UUID.randomUUID()));
            tooPrecise.put("amount", 49.001);
            given().auth().oauth2(token).contentType("application/json").body(tooPrecise).post(PATH)
                    .then().statusCode(400);
            verifyNoInteractions(stripe);
        } finally {
            execute("DELETE FROM account_roles WHERE account_id=?", actor);
        }
    }

    private Map<String, Object> request(UUID id) {
        return Map.of("id", id, "name", "Standard", "plan", "MONTHLY", "amount", 49,
                "vatPercent", 25, "vatInclusive", false);
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
}
