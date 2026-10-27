package com.timereport.invoicing.adapter.in.rest;

import com.timereport.authentication.AuthTestSupport;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class InvoiceDeliveryResourceTest {
    @Inject
    AuthTestSupport auth;
    @Inject
    MockMailbox mailbox;
    @Inject
    DataSource dataSource;

    @Test
    void archivedPdfDeliveryIsIdempotentAndAccountScopedAndKeepsPaymentStatusSeparate() throws Exception {
        var owner = auth.login();
        var other = auth.login();
        String token = owner.accessToken();
        String recipient = UUID.randomUUID() + "@example.test";
        String contact = UUID.randomUUID() + "@example.test";
        String customer = given().auth().oauth2(token).contentType("application/json").body(Map.of(
                        "customerNumber", "C-1", "name", "Customer AB", "addressLine", "Kundgatan 2", "postalCode", "54321",
                        "city", "Göteborg", "country", "Sverige", "invoiceEmail", recipient, "paymentTermsDays", 30, "contactEmail", contact))
                .post("/api/invoicing/customers").then().statusCode(201).extract().path("id");
        String code = "MAIL-" + UUID.randomUUID();
        given().auth().oauth2(token).contentType("application/json").body(Map.of("active", true, "expectedVersion", 0))
                .put("/api/customer-codes/" + code).then().statusCode(200);
        given().auth().oauth2(token).contentType("application/json").body(Map.of("customerId", customer,
                        "serviceDescription", "Konsultarbete", "hourlyRate", 1000, "vatPercent", 25))
                .put("/api/invoicing/customer-codes/" + code).then().statusCode(200);
        given().auth().oauth2(token).contentType("application/json").body(Map.of("date", "2026-01-05", "start", "08:00",
                        "end", "17:00", "deductedMinutes", 30, "customerCode", code, "comment", "Arbete", "expectedVersion", 0))
                .put("/api/time-entries/" + UUID.randomUUID()).then().statusCode(200);
        for (String endpoint : List.of("/api/invoicing/draft/pdf", "/api/invoicing/invoices")) {
            given().auth().oauth2(token).contentType("application/json").body(Map.of("month", "2026-01", "customerId", customer))
                    .post(endpoint).then().statusCode(400).contentType("application/json")
                    .body("code", equalTo("INVALID_INPUT"), "message", equalTo(
                            "Du har inte registrerat ditt företag. Fyll i och spara uppgifterna under Ditt företag först."));
        }
        given().auth().oauth2(token).get("/api/invoicing/invoices").then().statusCode(200).body("size()", equalTo(0));
        given().auth().oauth2(token).get("/api/invoicing/invoice-numbering").then().statusCode(200).body("nextNumber", equalTo(1));
        given().auth().oauth2(token).contentType("application/json").body(Map.ofEntries(
                        Map.entry("sellerName", "Example AB"), Map.entry("addressLine", "Testgatan 1"), Map.entry("postalCode", "12345"),
                        Map.entry("city", "Stockholm"), Map.entry("country", "Sverige"), Map.entry("currencyCode", "SEK"),
                        Map.entry("email", "company@example.test"), Map.entry("paymentTermsDays", 30),
                        Map.entry("defaultVatPercent", 25), Map.entry("fTaxRegistered", true)))
                .put("/api/invoicing/profile").then().statusCode(200);
        var issued = given().auth().oauth2(token).contentType("application/json").body(Map.of("month", "2026-01", "customerId", customer))
                .post("/api/invoicing/invoices").then().statusCode(200).extract().response();
        String number = issued.header("X-Invoice-Number");
        String path = "/api/invoicing/invoices/" + number;
        // A pre-V9 snapshot has no contactEmail; defaults must use the current customer card.
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE issued_invoices SET snapshot=snapshot #- '{customer,contactEmail}' WHERE account_id=? AND invoice_number=?")) {
            statement.setObject(1, UUID.fromString(owner.accountId()));
            statement.setInt(2, Integer.parseInt(number));
            statement.executeUpdate();
        }
        given().auth().oauth2(token).get(path + "/delivery-defaults").then().statusCode(200)
                .body("cc", contains(contact), "bcc", contains("company@example.test"));
        given().auth().oauth2(other.accessToken()).get(path + "/delivery-defaults").then().statusCode(404);
        byte[] pdf = issued.asByteArray();
        assertTrue(mailbox.getMailMessagesSentTo(recipient).isEmpty());
        given().auth().oauth2(token).get("/api/invoicing/invoices").then().statusCode(200)
                .header("Cache-Control", containsString("no-store"))
                .body("size()", equalTo(1), "[0].status.sent", equalTo(false), "[0].status.paid", equalTo(false),
                        "[0].status.version", equalTo(0), "[0].vatAmount", equalTo(2125.0f));
        given().auth().oauth2(other.accessToken()).get("/api/invoicing/invoices").then().statusCode(200).body("size()", equalTo(0));
        given().auth().oauth2(other.accessToken()).get(path + "/pdf").then().statusCode(404);
        UUID requestId = UUID.randomUUID();
        var send = Map.of("recipient", recipient, "requestId", requestId);
        given().auth().oauth2(other.accessToken()).contentType("application/json").body(send).post(path + "/email").then().statusCode(404);
        given().auth().oauth2(token).contentType("application/json").body(send).post(path + "/email")
                .then().statusCode(200).body("state", equalTo("SENT"));
        given().auth().oauth2(token).contentType("application/json").body(send).post(path + "/email")
                .then().statusCode(200).body("state", equalTo("SENT"));
        var messages = mailbox.getMailMessagesSentTo(recipient);
        assertEquals(1, messages.size());
        var message = mailbox.getMailsSentTo(recipient).getFirst();
        assertTrue(message.getFrom().contains("Example AB via Tidrapporten"));
        assertEquals("company@example.test", message.getReplyTo());
        assertEquals(List.of(contact), message.getCc());
        assertEquals(List.of("company@example.test"), message.getBcc());
        assertTrue(message.getText().contains("Vid frågor kan du skicka mejl till company@example.test."));
        assertEquals("application/pdf", message.getAttachments().getFirst().getContentType());
        var bytes = Multi.createFrom().publisher(message.getAttachments().getFirst().getData()).collect().asList().await().atMost(Duration.ofSeconds(5));
        byte[] attachment = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) attachment[i] = bytes.get(i);
        assertArrayEquals(pdf, attachment);
        given().auth().oauth2(token).contentType("application/json").body(Map.of("recipient", "other@example.test", "requestId", requestId))
                .post(path + "/email").then().statusCode(409);
        given().auth().oauth2(token).contentType("application/json").body(Map.of("recipient", recipient, "requestId", requestId,
                        "cc", List.of("different@example.test"), "bcc", List.of("company@example.test")))
                .post(path + "/email").then().statusCode(409);
        given().auth().oauth2(token).contentType("application/json").body(Map.of("sent", true, "paid", true, "expectedVersion", 1))
                .put(path + "/status").then().statusCode(200).body("paid", equalTo(true), "sent", equalTo(true), "version", equalTo(2));
        given().auth().oauth2(token).contentType("application/json").body(Map.of("sent", false, "paid", false, "expectedVersion", 1))
                .put(path + "/status").then().statusCode(409);
        given().auth().oauth2(other.accessToken()).contentType("application/json").body(Map.of("sent", false, "paid", false, "expectedVersion", 2))
                .put(path + "/status").then().statusCode(404);
        given().auth().oauth2(token).get("/api/invoicing/invoices").then().statusCode(200)
                .body("[0].status.paid", equalTo(true), "[0].status.sent", equalTo(true), "[0].lastDelivery.state", equalTo("SENT"));
        assertArrayEquals(pdf, given().auth().oauth2(token).get(path + "/pdf").then().statusCode(200).extract().asByteArray());
        var nextRequest = Map.of("recipient", recipient, "requestId", UUID.randomUUID());
        var results = java.util.stream.IntStream.range(0, 2).parallel().mapToObj(ignored ->
                given().auth().oauth2(token).contentType("application/json").body(nextRequest).post(path + "/email")
                        .then().statusCode(200).extract().path("state")).toList();
        assertTrue(results.stream().allMatch(state -> state.equals("SENDING") || state.equals("SENT")));
        assertEquals(2, mailbox.getMailMessagesSentTo(recipient).size());
        given().auth().oauth2(token).get("/api/invoicing/invoices").then().statusCode(200)
                .body("[0].status.paid", equalTo(true), "[0].status.sent", equalTo(true), "[0].status.version", equalTo(3),
                        "[0].lastDelivery.cc", contains(contact), "[0].lastDelivery.bcc", contains("company@example.test"));
        given().auth().oauth2(token).contentType("application/json").body(Map.of("recipient", recipient, "requestId", UUID.randomUUID(),
                        "cc", List.of("override@example.test"), "bcc", List.of()))
                .post(path + "/email").then().statusCode(200).body("cc", contains("override@example.test"), "bcc", empty());
        var override = mailbox.getMailsSentTo(recipient).getLast();
        assertEquals(List.of("override@example.test"), override.getCc());
        assertTrue(override.getBcc().isEmpty());
        given().auth().oauth2(token).contentType("application/json").body(Map.of("recipient", recipient, "requestId", UUID.randomUUID(),
                        "cc", List.of("bad\r\nBcc:evil@example.test")))
                .post(path + "/email").then().statusCode(400);
        given().auth().oauth2(token).contentType("application/json").body(Map.of("recipient", "bad\r\nBcc:evil@example.test", "requestId", UUID.randomUUID()))
                .post(path + "/email").then().statusCode(400);
        given().get(path + "/pdf").then().statusCode(401);
    }
}
