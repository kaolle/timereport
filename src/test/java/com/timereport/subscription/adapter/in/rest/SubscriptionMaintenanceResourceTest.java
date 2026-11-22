package com.timereport.subscription.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import com.timereport.subscription.domain.MaintenanceResult;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SubscriptionMaintenanceResourceTest.Profile.class)
class SubscriptionMaintenanceResourceTest {
    @Test
    void maintenanceIsPublicOnlyToTheConfiguredSchedulerToken() {
        given().post("/internal/subscription/maintain").then().statusCode(403);
        given().header("X-Subscription-Maintenance-Token", "wrong")
                .post("/internal/subscription/maintain").then().statusCode(403);
        given().header("X-Mail-Dispatch-Token", "test-only-subscription-maintenance")
                .post("/internal/subscription/maintain").then().statusCode(403);
        given().header("X-Subscription-Maintenance-Token", "test-only-subscription-maintenance")
                .post("/internal/subscription/maintain").then().statusCode(200)
                .header("Cache-Control", "no-store").body("checked", equalTo(0)).body("failed", equalTo(0));
    }

    @Test
    void partialFailureReturnsCountsWithRetryableStatus() {
        var resource = new SubscriptionMaintenanceResource(() -> new MaintenanceResult(3, 1, 1, 0), Optional.of("scheduler-token"));
        try (var response = resource.maintain("scheduler-token")) {
            assertEquals(503, response.getStatus());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
            var body = (SubscriptionMaintenanceResource.MaintenanceResponse) response.getEntity();
            assertEquals(3, body.checked());
            assertEquals(1, body.failed());
            assertEquals(1, body.remindersQueued());
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("subscription.maintenance-token", "test-only-subscription-maintenance");
        }
    }
}
