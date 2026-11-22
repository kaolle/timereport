package com.timereport.subscription.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static com.timereport.subscription.adapter.in.rest.StripeWebhookVerifierTest.*;

@QuarkusTest
@TestProfile(StripeWebhookResourceTest.Profile.class)
class StripeWebhookResourceTest {
    private static final String PATH = "/api/subscription/webhooks/stripe";

    @Test
    void signedWebhookIsTheOnlyPublicSubscriptionRoute() {
        long now = Instant.now().getEpochSecond();
        String body = body(now).replace("invoice.paid", "customer.updated");
        given().contentType(ContentType.JSON).body(body).header("Stripe-Signature", sign(body, now))
                .post(PATH).then().statusCode(200).header("Cache-Control", "no-store")
                .body("status", equalTo("IGNORED"));
        given().get("/api/subscription").then().statusCode(401);
        given().post("/api/subscription/portal").then().statusCode(401);
    }

    @Test
    void missingAndTamperedSignaturesAreRejectedWithoutJwt() {
        long now = Instant.now().getEpochSecond();
        String body = body(now);
        given().contentType(ContentType.JSON).body(body).post(PATH).then().statusCode(400);
        given().contentType(ContentType.JSON).body(body.replace("in_test", "in_tampered"))
                .header("Stripe-Signature", sign(body, now)).post(PATH).then().statusCode(400);
    }

    @Test
    void limitsRawBodySize() {
        given().contentType(ContentType.JSON).body(" ".repeat(65_537)).header("Stripe-Signature", "t=1,v1=bad")
                .post(PATH).then().statusCode(413);
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("subscription.webhooks-enabled", "true", "subscription.stripe.webhook-secret", SECRET);
        }
    }
}
