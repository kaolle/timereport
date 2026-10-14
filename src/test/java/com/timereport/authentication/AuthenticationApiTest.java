package com.timereport.authentication;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class AuthenticationApiTest {
    @Inject
    AuthTestSupport support;
    @Inject
    DataSource dataSource;

    @Test
    void anonymousAndForgedTokensCannotReachAnyBusinessApi() {
        for (String path : new String[]{"/api/customer-codes", "/api/time-entries?from=2026-10-01&to=2026-10-31", "/api/vacation-days?from=2026-10-01&to=2026-10-31", "/api/auth/me"}) {
            given().get(path).then().statusCode(401);
            given().auth().oauth2("not-a-jwt").get(path).then().statusCode(401);
        }
    }

    @Test
    void requiresEmailConfirmationAndHidesAccountExistence() {
        String email = UUID.randomUUID() + "@example.test";
        for (int i = 0; i < 2; i++) {
            given().header("X-Requested-With", "timereport").contentType("application/json")
                    .body(Map.of("email", email)).post("/api/auth/register")
                    .then().statusCode(202).body("accessToken", nullValue());
        }
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", email, "password", AuthTestSupport.PASSWORD)).post("/api/auth/login").then().statusCode(401);
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", UUID.randomUUID() + "@example.test")).post("/api/auth/forgot-password")
                .then().statusCode(202).body("token", nullValue());
    }

    @Test
    void verificationSetsChosenPasswordAndCannotBeReusedToOverwriteIt() {
        String email = UUID.randomUUID() + "@example.test";
        var headers = given().header("X-Requested-With", "timereport").contentType("application/json");
        headers.body(Map.of("email", email)).post("/api/auth/register").then().statusCode(202);
        String oldToken = support.mailToken(email, "verify");
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", email)).post("/api/auth/register").then().statusCode(202);
        String token = support.mailToken(email, "verify");
        String password = "chosen only after opening the link 123!";
        for (var body : java.util.List.of(Map.of("token", oldToken, "password", password),
                Map.of("token", "forged-token", "password", password))) {
            given().header("X-Requested-With", "timereport").contentType("application/json")
                    .body(body).post("/api/auth/verify-email").then().statusCode(401);
        }
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("token", token, "password", "too short"))
                .post("/api/auth/verify-email").then().statusCode(400);
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("token", token, "password", password))
                .post("/api/auth/verify-email").then().statusCode(204);
        support.login(email, password);
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("token", token, "password", AuthTestSupport.PASSWORD))
                .post("/api/auth/verify-email").then().statusCode(401);
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", email)).post("/api/auth/register").then().statusCode(202);
        support.login(email, password);
    }

    @Test
    void refreshRotatesAndReplayCommitsSessionRevocation() {
        var login = support.login();
        var rotated = given().header("X-Requested-With", "timereport").cookie("timereport_refresh", login.refreshToken())
                .post("/api/auth/refresh").then().statusCode(200)
                .header("Set-Cookie", allOf(containsString("HttpOnly"), containsString("SameSite=Strict"), containsString("Path=/api/auth")))
                .header("Cache-Control", "no-store").body("expiresIn", equalTo(300)).body("refreshToken", nullValue()).extract().response();
        assertNotEquals(login.refreshToken(), rotated.cookie("timereport_refresh"));
        given().header("X-Requested-With", "timereport").cookie("timereport_refresh", login.refreshToken())
                .post("/api/auth/refresh").then().statusCode(401);
        given().header("X-Requested-With", "timereport").cookie("timereport_refresh", rotated.cookie("timereport_refresh"))
                .post("/api/auth/refresh").then().statusCode(401);
        given().auth().oauth2(rotated.path("accessToken")).get("/api/customer-codes").then().statusCode(401);
    }

    @Test
    void logoutRevokesAccessAndRefresh() {
        var login = support.login();
        given().header("X-Requested-With", "timereport").cookie("timereport_refresh", login.refreshToken())
                .post("/api/auth/logout").then().statusCode(204).header("Set-Cookie", containsString("Max-Age=0"));
        given().auth().oauth2(login.accessToken()).get("/api/auth/me").then().statusCode(401);
        given().header("X-Requested-With", "timereport").cookie("timereport_refresh", login.refreshToken())
                .post("/api/auth/refresh").then().statusCode(401);
    }

    @Test
    void passwordChangeRequiresOldPasswordAndRevokesEverySession() {
        var first = support.login();
        var second = support.login(first.email(), AuthTestSupport.PASSWORD);
        var body = Map.of("currentPassword", "wrong password", "newPassword", "a different long password 456!");
        given().auth().oauth2(first.accessToken()).header("X-Requested-With", "timereport").contentType("application/json")
                .body(body).post("/api/auth/change-password").then().statusCode(401);
        body = Map.of("currentPassword", AuthTestSupport.PASSWORD, "newPassword", "a different long password 456!");
        given().auth().oauth2(first.accessToken()).header("X-Requested-With", "timereport").contentType("application/json")
                .body(body).post("/api/auth/change-password").then().statusCode(204);
        given().auth().oauth2(second.accessToken()).get("/api/auth/me").then().statusCode(401);
        support.login(first.email(), "a different long password 456!");
    }

    @Test
    void resetIsSingleUseAndRevokesSessions() {
        var login = support.login();
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", login.email())).post("/api/auth/forgot-password").then().statusCode(202);
        String token = support.mailToken(login.email(), "reset");
        var body = Map.of("token", token, "password", "my replacement password 456!");
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(body).post("/api/auth/reset-password").then().statusCode(204);
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(body).post("/api/auth/reset-password").then().statusCode(401);
        given().auth().oauth2(login.accessToken()).get("/api/auth/me").then().statusCode(401);
        support.login(login.email(), "my replacement password 456!");
    }

    @Test
    void expiresResetAndRefreshTokensAndDoesNotStoreRawRefresh() throws Exception {
        var login = support.login();
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("SELECT count(*) FROM refresh_tokens WHERE token_hash=?")) {
            s.setString(1, login.refreshToken());
            try (var r = s.executeQuery()) {
                r.next();
                assertEquals(0, r.getInt(1));
            }
        }
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", login.email())).post("/api/auth/forgot-password").then().statusCode(202);
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("UPDATE account_tokens SET expires_at=now()-interval '1 second' WHERE account_id=?")) {
            s.setObject(1, UUID.fromString(login.accountId()));
            s.executeUpdate();
        }
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("token", support.mailToken(login.email(), "reset"), "password", AuthTestSupport.PASSWORD))
                .post("/api/auth/reset-password").then().statusCode(401);
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("UPDATE auth_sessions SET expires_at=now()-interval '1 second' WHERE account_id=?")) {
            s.setObject(1, UUID.fromString(login.accountId()));
            s.executeUpdate();
        }
        given().header("X-Requested-With", "timereport").cookie("timereport_refresh", login.refreshToken())
                .post("/api/auth/refresh").then().statusCode(401);
    }

    @Test
    void rejectsCsrfAndRateLimitsRepeatedRequests() {
        given().post("/api/auth/refresh").then().statusCode(403);
        given().header("X-Requested-With", "timereport").header("Origin", "https://evil.example")
                .post("/api/auth/refresh").then().statusCode(403);
        String email = UUID.randomUUID() + "@example.test";
        for (int i = 0; i < 10; i++) {
            given().header("X-Requested-With", "timereport").contentType("application/json")
                    .body(Map.of("email", email)).post("/api/auth/forgot-password").then().statusCode(202);
        }
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", email)).post("/api/auth/forgot-password").then().statusCode(429);
    }

    @Test
    void expiredAndWrongAudienceJwtAreRejected() {
        var login = support.login();
        String expired = Jwt.issuer("timereport").audience("timereport").subject(login.accountId())
                .groups(Set.of("user")).issuedAt(java.time.Instant.now().minusSeconds(600)).expiresAt(java.time.Instant.now().minusSeconds(120)).sign();
        given().auth().oauth2(expired).get("/api/customer-codes").then().statusCode(401);
        String otherAudience = Jwt.issuer("timereport").audience("other").subject(login.accountId()).sign();
        given().auth().oauth2(otherAudience).get("/api/customer-codes").then().statusCode(401);
    }

    @Test
    void accountIsolationIncludesSameCodesIdsDatesVacationAndUpdates() {
        var a = support.login();
        var b = support.login();
        String id = UUID.randomUUID().toString();
        for (var user : new AuthTestSupport.Login[]{a, b}) {
            given().auth().oauth2(user.accessToken()).contentType("application/json").body(Map.of("active", true, "expectedVersion", 0))
                    .put("/api/customer-codes/FAIR1234").then().statusCode(200);
        }
        var entry = Map.of("date", "2026-10-12", "start", "08:00", "end", "17:00", "customerCode", "FAIR1234", "expectedVersion", 0, "comment", "private A");
        given().auth().oauth2(a.accessToken()).contentType("application/json").body(entry).put("/api/time-entries/" + id).then().statusCode(200);
        given().auth().oauth2(b.accessToken()).get("/api/time-entries/" + id).then().statusCode(404);
        given().auth().oauth2(b.accessToken()).delete("/api/time-entries/" + id + "?expectedVersion=1").then().statusCode(404);
        given().auth().oauth2(b.accessToken()).get("/api/time-entries?from=2026-10-12&to=2026-10-12").then().body("size()", equalTo(0));
        given().auth().oauth2(b.accessToken()).contentType("application/json").body(entry).put("/api/time-entries/" + id).then().statusCode(200);
        given().auth().oauth2(b.accessToken()).put("/api/vacation-days/2026-10-12").then().statusCode(204);
        given().auth().oauth2(a.accessToken()).get("/api/time-entries/" + id).then().statusCode(200).body("comment", equalTo("private A"));
        given().auth().oauth2(a.accessToken()).get("/api/vacation-days?from=2026-10-12&to=2026-10-12").then().body("size()", equalTo(0));
    }
}
