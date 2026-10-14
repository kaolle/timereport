package com.timereport.authentication;

import io.quarkus.mailer.MockMailbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;

@ApplicationScoped
public class AuthTestSupport {
    public static final String PASSWORD = "a long test password 123!";
    @Inject
    MockMailbox mailbox;

    public record Login(String email, String accessToken, String refreshToken, String accountId) {
    }

    public Login login() {
        String email = UUID.randomUUID() + "@example.test";
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", email)).post("/api/auth/register").then().statusCode(202);
        given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("token", mailToken(email, "verify"), "password", PASSWORD))
                .post("/api/auth/verify-email").then().statusCode(204);
        return login(email, PASSWORD);
    }

    public Login login(String email, String password) {
        var response = given().header("X-Requested-With", "timereport").contentType("application/json")
                .body(Map.of("email", email, "password", password)).post("/api/auth/login").then().statusCode(200).extract().response();
        return new Login(email, response.path("accessToken"), response.cookie("timereport_refresh"), response.path("account.accountId"));
    }

    public String mailToken(String email, String purpose) {
        var messages = mailbox.getMailMessagesSentTo(email);
        String text = messages.get(messages.size() - 1).getText();
        var matcher = Pattern.compile("#" + purpose + "=([A-Za-z0-9_-]+)").matcher(text);
        if (!matcher.find()) throw new AssertionError("Token link missing");
        return matcher.group(1);
    }
}
