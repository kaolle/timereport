package com.timereport.authentication;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestProfile(DirectRegistrationApiTest.DirectRegistrationProfile.class)
class DirectRegistrationApiTest {
    @Test
    void registrationWithPasswordCreatesVerifiedAccountAndLogsInWithoutEmail() {
        String email = UUID.randomUUID() + "@example.test";
        String password = "temporary direct registration password 7!";
        String accessToken = given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", email, "password", password)).post("/api/auth/register")
                .then().statusCode(200).body("account.email", equalTo(email)).body("accessToken", not(isEmptyOrNullString()))
                .extract().path("accessToken");

        given().auth().oauth2(accessToken).get("/api/auth/me").then().statusCode(200)
                .body("email", equalTo(email));
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", email, "password", password)).post("/api/auth/login").then()
                .statusCode(200).body("account.email", equalTo(email));
    }

    public static class DirectRegistrationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("auth.email-verification-required", "false");
        }
    }
}
