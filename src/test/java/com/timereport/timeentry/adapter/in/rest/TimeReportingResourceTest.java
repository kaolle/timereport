package com.timereport.timeentry.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class TimeReportingResourceTest {
    private static final AtomicInteger DAYS = new AtomicInteger();
    private String code;
    private String accessToken;
    @jakarta.inject.Inject
    com.timereport.authentication.AuthTestSupport authSupport;
    private String date;

    @BeforeEach
    void createUniqueCustomerAndDay() {
        accessToken = authSupport.login().accessToken();
        code = "TEST-" + UUID.randomUUID();
        date = LocalDate.of(2040, 1, 1).plusDays(DAYS.getAndIncrement()).toString();
        given().auth().oauth2(accessToken).contentType(ContentType.JSON).body(Map.of("active", true, "expectedVersion", 0))
                .when().put("/api/customer-codes/" + code).then().statusCode(200)
                .body("code", equalTo(code), "version", equalTo(1));
    }

    @Test
    void createReadEditListAndDeleteRoundTrip() {
        UUID id = UUID.randomUUID();
        var body = entry(0);
        put(id, body, 200);
        given().auth().oauth2(accessToken).when().get("/api/time-entries/" + id).then().statusCode(200)
                .body("workedMinutes", equalTo(510), "deductedMinutes", equalTo(30),
                        "customerCode", equalTo(code), "comment", equalTo("Implementation"), "version", equalTo(1));
        body.put("expectedVersion", 1);
        body.put("deductedMinutes", 15);
        put(id, body, 200);
        given().auth().oauth2(accessToken).queryParam("from", date).queryParam("to", date).get("/api/time-entries").then()
                .statusCode(200).body("size()", equalTo(1), "[0].workedMinutes", equalTo(525), "[0].version", equalTo(2));
        given().auth().oauth2(accessToken).queryParam("expectedVersion", 2).delete("/api/time-entries/" + id).then().statusCode(204);
        given().auth().oauth2(accessToken).get("/api/time-entries/" + id).then().statusCode(404).body("code", equalTo("NOT_FOUND"));
        given().auth().oauth2(accessToken).queryParam("from", date).queryParam("to", date).get("/api/time-entries").then()
                .statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void repeatedRequestsAreIdempotentAndStaleChangesConflict() {
        UUID id = UUID.randomUUID();
        var body = entry(0);
        put(id, body, 200);
        put(id, body, 200);
        body.put("comment", "Changed");
        put(id, body, 409);
        body.put("expectedVersion", 1);
        put(id, body, 200);
        put(id, body, 200);
        given().auth().oauth2(accessToken).get("/api/time-entries/" + id).then().body("version", equalTo(2));
        given().auth().oauth2(accessToken).queryParam("expectedVersion", 1).delete("/api/time-entries/" + id).then().statusCode(409);
        given().auth().oauth2(accessToken).queryParam("expectedVersion", 2).delete("/api/time-entries/" + id).then().statusCode(204);
        given().auth().oauth2(accessToken).queryParam("expectedVersion", 2).delete("/api/time-entries/" + id).then().statusCode(204);
        put(id, entry(0), 409);
        body.put("expectedVersion", 3);
        put(id, body, 409);
    }

    @Test
    void rejectsInactiveCodesButKeepsHistory() {
        UUID id = UUID.randomUUID();
        put(id, entry(0), 200);
        var deactivate = Map.of("active", false, "expectedVersion", 1);
        given().auth().oauth2(accessToken).contentType(ContentType.JSON).body(deactivate).put("/api/customer-codes/" + code)
                .then().statusCode(200).body("active", equalTo(false), "version", equalTo(2));
        given().auth().oauth2(accessToken).contentType(ContentType.JSON).body(deactivate).put("/api/customer-codes/" + code)
                .then().statusCode(200).body("version", equalTo(2));
        put(UUID.randomUUID(), entry(0), 400);
        var correction = entry(1);
        correction.put("comment", "Historical correction");
        put(id, correction, 200);
        given().auth().oauth2(accessToken).get("/api/customer-codes").then().statusCode(200).body("code", hasItem(code));
    }

    @Test
    void allowsMissingBlankAndNullCodesAndCreatesInlineCodes() {
        UUID id = UUID.randomUUID();
        var body = entry(0);
        body.remove("customerCode");
        put(id, body, 200);
        given().auth().oauth2(accessToken).get("/api/time-entries/" + id)
                .then().statusCode(200).body("customerCode", nullValue(), "workedMinutes", equalTo(510));
        body.put("expectedVersion", 1);
        body.put("customerCode", "  INLINE123  ");
        put(id, body, 200);
        given().auth().oauth2(accessToken).get("/api/customer-codes")
                .then().statusCode(200).body("code", hasItem("INLINE123"));
        for (int version = 2; version <= 3; version++) {
            body.put("expectedVersion", version);
            body.put("customerCode", version == 2 ? " " : null);
            put(id, body, 200);
            given().auth().oauth2(accessToken).get("/api/time-entries/" + id)
                    .then().statusCode(200).body("customerCode", nullValue());
        }
    }

    @Test
    void validatesRequiredFieldsInvalidTimesAndMalformedJson() {
        var missing = entry(0);
        missing.remove("date");
        put(UUID.randomUUID(), missing, 400);
        missing = entry(0);
        missing.remove("expectedVersion");
        put(UUID.randomUUID(), missing, 400);
        var backwards = entry(0);
        backwards.put("end", "07:00");
        put(UUID.randomUUID(), backwards, 400);
        var excessive = entry(0);
        excessive.put("deductedMinutes", 600);
        put(UUID.randomUUID(), excessive, 400);
        var malformed = entry(0);
        malformed.put("date", "not-a-date");
        put(UUID.randomUUID(), malformed, 400);
        given().auth().oauth2(accessToken).contentType(ContentType.JSON).body("{").put("/api/time-entries/" + UUID.randomUUID())
                .then().statusCode(400);
        given().auth().oauth2(accessToken).contentType(ContentType.JSON).body("null").put("/api/time-entries/" + UUID.randomUUID())
                .then().statusCode(400);
        given().auth().oauth2(accessToken).delete("/api/time-entries/" + UUID.randomUUID()).then().statusCode(400);
    }

    @Test
    void listRequiresAValidInclusiveDateRange() {
        given().auth().oauth2(accessToken).get("/api/time-entries").then().statusCode(400);
        given().auth().oauth2(accessToken).queryParam("from", "bad").queryParam("to", date).get("/api/time-entries").then().statusCode(400);
        given().auth().oauth2(accessToken).queryParam("from", date).queryParam("to", "2000-01-01").get("/api/time-entries").then().statusCode(400);
        put(UUID.randomUUID(), entry(0), 200);
        given().auth().oauth2(accessToken).queryParam("from", LocalDate.parse(date).plusDays(1).toString()).queryParam("to", "2099-01-01")
                .get("/api/time-entries").then().statusCode(200).body("date", not(hasItem(date)));
    }

    @Test
    void vacationDaysPersistAndCanBeRemovedAcrossRequests() {
        put(UUID.randomUUID(), entry(0), 200);
        given().auth().oauth2(accessToken).put("/api/vacation-days/" + date).then().statusCode(204);
        given().auth().oauth2(accessToken).queryParam("from", date).queryParam("to", date).get("/api/vacation-days").then()
                .statusCode(200).body("size()", equalTo(1), "[0].date", equalTo(date));
        given().auth().oauth2(accessToken).queryParam("from", date).queryParam("to", date).get("/api/time-entries").then()
                .statusCode(200).body("size()", equalTo(0));
        given().auth().oauth2(accessToken).put("/api/vacation-days/" + date).then().statusCode(204);
        given().auth().oauth2(accessToken).delete("/api/vacation-days/" + date).then().statusCode(204);
        given().auth().oauth2(accessToken).queryParam("from", date).queryParam("to", date).get("/api/vacation-days").then()
                .statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void overlapsConflictButAdjacentIntervalsAndDeletedSlotsAreAllowed() {
        UUID id = UUID.randomUUID();
        put(id, entry(0), 200);
        put(UUID.randomUUID(), entry(0), 409);
        var adjacent = entry(0);
        adjacent.put("start", "17:00");
        adjacent.put("end", "18:00");
        adjacent.put("deductedMinutes", 0);
        put(UUID.randomUUID(), adjacent, 200);
        given().auth().oauth2(accessToken).queryParam("expectedVersion", 1).delete("/api/time-entries/" + id).then().statusCode(204);
        put(UUID.randomUUID(), entry(0), 200);
    }

    @Test
    void concurrentOverlappingCreatesHaveExactlyOneWinner() throws Exception {
        var barrier = new CyclicBarrier(2);
        Callable<Integer> create = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return given().auth().oauth2(accessToken).contentType(ContentType.JSON).body(entry(0))
                    .put("/api/time-entries/" + UUID.randomUUID()).statusCode();
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(create);
            var second = executor.submit(create);
            var statuses = new ArrayList<>(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(java.util.List.of(200, 409), statuses);
        }
    }

    @Test
    void concurrentIdenticalCreatesAreBothSuccessfulWithOneStoredVersion() throws Exception {
        UUID id = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        Callable<Integer> create = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return given().auth().oauth2(accessToken).contentType(ContentType.JSON).body(entry(0)).put("/api/time-entries/" + id).statusCode();
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertEquals(200, first.get(20, TimeUnit.SECONDS));
            assertEquals(200, second.get(20, TimeUnit.SECONDS));
        }
        given().auth().oauth2(accessToken).get("/api/time-entries/" + id).then().body("version", equalTo(1));
    }

    private Map<String, Object> entry(long version) {
        var body = new HashMap<String, Object>();
        body.put("date", date);
        body.put("start", "08:00");
        body.put("end", "17:00");
        body.put("customerCode", code);
        body.put("comment", "Implementation");
        body.put("expectedVersion", version);
        return body;
    }

    private void put(UUID id, Map<String, Object> body, int status) {
        given().auth().oauth2(accessToken).contentType(ContentType.JSON).body(body).put("/api/time-entries/" + id).then().statusCode(status);
    }
}
