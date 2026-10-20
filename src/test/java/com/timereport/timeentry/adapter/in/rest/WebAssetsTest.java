package com.timereport.timeentry.adapter.in.rest;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class WebAssetsTest {
    @Test
    void healthProbesAreAvailableWithoutLoginAndDatabaseIsReady() {
        for (String probe : new String[]{"started", "live", "ready"}) {
            given().get("/q/health/" + probe).then().statusCode(200).body("status", equalTo("UP"));
        }
    }

    @Test
    void servesTheMobileAppAndOfflineInstallationAssets() {
        given().get("/").then().statusCode(200).contentType("text/html")
                .body(containsString("lang=\"sv\""), containsString("/js/app.js"), containsString("/manifest.json"));
        given().get("/manifest.json").then().statusCode(200)
                .body("display", equalTo("standalone"), "start_url", equalTo("/"));
        given().get("/sw.js").then().statusCode(200).body(containsString("timereport-shell-"));
        given().get("/js/monthly-report.js").then().statusCode(200).body(containsString("buildMonthlyReport"));
        given().get("/js/report.js").then().statusCode(200).body(containsString("configureMonthlyReport"));
        given().get("/icons/icon-192.png").then().statusCode(200).contentType("image/png");
    }
}
