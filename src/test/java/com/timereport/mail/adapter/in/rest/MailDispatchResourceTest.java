package com.timereport.mail.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@QuarkusTest
@TestProfile(MailDispatchResourceTest.Profile.class)
class MailDispatchResourceTest {
    @Test
    void onlyConfiguredWorkerCanDrainTheQueue() {
        given().post("/internal/mail/dispatch").then().statusCode(403);
        given().header("X-Mail-Dispatch-Token", "wrong").post("/internal/mail/dispatch").then().statusCode(403);
        given().header("X-Mail-Dispatch-Token", "test-only-dispatch-secret")
                .post("/internal/mail/dispatch").then().statusCode(200).body("processed", equalTo(0));
    }

    @Test
    void onlyConfiguredWorkerCanCleanTheQueue() {
        given().post("/internal/mail/cleanup").then().statusCode(403);
        given().header("X-Mail-Dispatch-Token", "wrong").post("/internal/mail/cleanup").then().statusCode(403);
        given().header("X-Mail-Dispatch-Token", "test-only-dispatch-secret")
                .post("/internal/mail/cleanup").then().statusCode(200)
                .body("deleted", greaterThanOrEqualTo(0));
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("mail.dispatch-token", "test-only-dispatch-secret");
        }
    }
}
