package com.timereport.appearance.adapter.in.rest;

import com.timereport.authentication.AuthTestSupport;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AppearanceResourceTest {
    @Inject AuthTestSupport auth;
    @Inject DataSource dataSource;

    @Test
    void globalThemeIsPublicButOnlyCurrentAdminsCanChangeIt() throws SQLException {
        given().get("/api/appearance").then().statusCode(200)
                .header("Cache-Control", "no-store").body("theme", equalTo("STANDARD"));
        given().contentType("application/json").body("{\"theme\":\"CHRISTMAS\"}")
                .post("/api/admin/appearance").then().statusCode(401);
        var ordinary = auth.login();
        UUID accountId = UUID.fromString(ordinary.accountId());
        try {
            change(ordinary.accessToken(), "CHRISTMAS", 403);
            execute("INSERT INTO account_roles(account_id,role) VALUES (?,'admin')", accountId);
            change(ordinary.accessToken(), "CHRISTMAS", 403);
            String token = auth.login(ordinary.email(), AuthTestSupport.PASSWORD).accessToken();
            change(token, "CHRISTMAS", 200);
            given().get("/api/appearance").then().statusCode(200).body("theme", equalTo("CHRISTMAS"));
            change(token, "UNSAFE_CUSTOM_THEME", 400);
            given().auth().oauth2(token).contentType("application/json").body("{}")
                    .post("/api/admin/appearance").then().statusCode(400);
            change(token, "STANDARD", 200);
            execute("DELETE FROM account_roles WHERE account_id=?", accountId);
            change(token, "CHRISTMAS", 403);
            given().get("/api/appearance").then().statusCode(200).body("theme", equalTo("STANDARD"));
        } finally {
            execute("UPDATE application_appearance SET theme='STANDARD' WHERE id=1");
            execute("DELETE FROM account_roles WHERE account_id=?", accountId);
        }
    }

    private void change(String token, String theme, int status) {
        given().auth().oauth2(token).contentType("application/json").body(java.util.Map.of("theme", theme))
                .post("/api/admin/appearance").then().statusCode(status);
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
}
