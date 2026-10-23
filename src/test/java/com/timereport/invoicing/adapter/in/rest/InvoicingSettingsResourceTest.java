package com.timereport.invoicing.adapter.in.rest;

import com.timereport.authentication.AuthTestSupport;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class InvoicingSettingsResourceTest {
    @Inject AuthTestSupport auth;

    @Test
    void sellerProfileCustomerAndCustomerCodeAssignmentAreScopedToAccount() {
        String first = auth.login().accessToken();
        String second = auth.login().accessToken();
        var profile = Map.ofEntries(Map.entry("sellerName", "Example AB"), Map.entry("addressLine", "Testgatan 1"),
                Map.entry("postalCode", "12345"), Map.entry("city", "Stockholm"), Map.entry("country", "Sverige"),
                Map.entry("organizationNumber", "556000-0000"), Map.entry("vatNumber", "SE556000000001"),
                Map.entry("bankgiro", "123-4567"), Map.entry("email", "invoice@example.test"), Map.entry("currencyCode", "SEK"),
                Map.entry("paymentTermsDays", 30), Map.entry("defaultVatPercent", 25), Map.entry("fTaxRegistered", true));
        given().auth().oauth2(first).contentType(ContentType.JSON).body(profile).put("/api/invoicing/profile")
                .then().statusCode(200).body("sellerName", equalTo("Example AB"), "defaultVatPercent", equalTo(25.0f));
        given().auth().oauth2(second).get("/api/invoicing/profile").then().statusCode(204);

        var customer = Map.ofEntries(Map.entry("customerNumber", "C-1"), Map.entry("name", "Customer AB"),
                Map.entry("addressLine", "Kundgatan 2"), Map.entry("postalCode", "54321"), Map.entry("city", "Göteborg"),
                Map.entry("country", "Sverige"), Map.entry("organizationNumber", ""), Map.entry("vatNumber", ""),
                Map.entry("invoiceEmail", ""), Map.entry("customerReference", "Kundref"), Map.entry("ourReference", "Konsult"),
                Map.entry("paymentTermsDays", 30));
        String customerId = given().auth().oauth2(first).contentType(ContentType.JSON).body(customer)
                .post("/api/invoicing/customers").then().statusCode(201).body("name", equalTo("Customer AB"))
                .extract().path("id");
        given().auth().oauth2(second).get("/api/invoicing/customers").then().statusCode(200).body("size()", equalTo(0));

        String code = "BILL-" + UUID.randomUUID();
        given().auth().oauth2(first).contentType(ContentType.JSON)
                .body(Map.of("active", true, "expectedVersion", 0)).put("/api/customer-codes/" + code).then().statusCode(200);
        var mapping = Map.of("customerId", customerId, "serviceDescription", "Konsulttjänster", "hourlyRate", 920.00,
                "vatPercent", 25.00);
        given().auth().oauth2(first).contentType(ContentType.JSON).body(mapping)
                .put("/api/invoicing/customer-codes/" + code).then().statusCode(200)
                .body("customerId", equalTo(customerId), "hourlyRate", equalTo(920.0f));
        given().auth().oauth2(second).contentType(ContentType.JSON).body(mapping)
                .put("/api/invoicing/customer-codes/" + code).then().statusCode(409);
        given().auth().oauth2(first).delete("/api/invoicing/customers/" + customerId).then().statusCode(409);

        given().auth().oauth2(first).delete("/api/invoicing/customer-codes/" + code).then().statusCode(204);
        given().auth().oauth2(first).delete("/api/invoicing/customers/" + customerId).then().statusCode(204);
        given().auth().oauth2(first).get("/api/customer-codes").then().statusCode(200).body("code", hasItem(code));
    }
}
