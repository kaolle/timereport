package com.timereport.subscription.adapter.in.rest;

import com.timereport.authentication.AuthTestSupport;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SubscriptionResourceTest {
    @Inject AuthTestSupport auth;

    @Test
    void checkoutAndPortalRequireAnAccountAndRemainDisabledUntilSandboxSetup() {
        given().get("/api/subscription").then().statusCode(401);
        given().contentType(ContentType.JSON).body(Map.of("plan", "MONTHLY"))
                .post("/api/subscription/checkout").then().statusCode(401);
        given().post("/api/subscription/portal").then().statusCode(401);
        String token = auth.login().accessToken();
        given().auth().oauth2(token).get("/api/subscription").then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("status", equalTo("NONE"), "ownerAccess", equalTo(false),
                        "entitled", equalTo(false), "invoicingAvailable", equalTo(true));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("plan", "MONTHLY"))
                .post("/api/subscription/checkout").then().statusCode(503)
                .header("Cache-Control", "no-store").body("code", equalTo("BILLING_UNAVAILABLE"));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("plan", "YEARLY"))
                .post("/api/subscription/checkout").then().statusCode(503)
                .body("code", equalTo("BILLING_UNAVAILABLE"));
        given().auth().oauth2(token).post("/api/subscription/portal").then().statusCode(503)
                .header("Cache-Control", "no-store").body("code", equalTo("BILLING_UNAVAILABLE"));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("plan", "UNLISTED"))
                .post("/api/subscription/checkout").then().statusCode(400);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of())
                .post("/api/subscription/checkout").then().statusCode(400);
    }
}
