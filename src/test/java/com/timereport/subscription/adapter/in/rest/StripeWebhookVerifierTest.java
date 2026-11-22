package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.domain.BillingEventResult;
import com.timereport.subscription.application.port.in.ProcessSubscriptionEvents;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StripeWebhookVerifierTest {
    static final String SECRET = "whsec_unit_test_not_a_real_secret";
    static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private final StripeWebhookVerifier verifier = new StripeWebhookVerifier(true, SECRET,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void verifiesOriginalPayloadAndOnlyPassesReferencesAcrossThePort() {
        String body = body(NOW.getEpochSecond());
        var result = verifier.verify(bytes(body), sign(body, NOW.getEpochSecond())).orElseThrow();

        assertEquals("evt_webhook_test", result.id());
        assertEquals("invoice.paid", result.type());
        assertEquals("in_test", result.objectId());
        assertEquals(NOW, result.createdAt());
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, 301})
    void rejectsBothExpiredAndFutureSignatures(long delta) {
        String body = body(NOW.getEpochSecond());
        assertThrows(BadRequestException.class,
                () -> verifier.verify(bytes(body), sign(body, NOW.getEpochSecond() + delta)));
    }

    @Test
    void acceptsOldEventsWhenTheirCurrentDeliverySignatureIsValid() {
        String body = body(NOW.minusSeconds(86_400).getEpochSecond());
        assertEquals(NOW.minusSeconds(86_400), verifier.verify(bytes(body),
                sign(body, NOW.getEpochSecond())).orElseThrow().createdAt());
    }

    @Test
    void acceptsLogicalFutureEventTimeWithAValidCurrentDeliverySignature() {
        String body = body(NOW.plusSeconds(86_400).getEpochSecond());
        assertEquals(NOW.plusSeconds(86_400), verifier.verify(bytes(body),
                sign(body, NOW.getEpochSecond())).orElseThrow().createdAt());
    }

    @Test
    void rejectsPayloadChangesAfterSigning() {
        String original = body(NOW.getEpochSecond());
        assertThrows(BadRequestException.class, () -> verifier.verify(bytes(original.replace("in_test", "in_other")),
                sign(original, NOW.getEpochSecond())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"livemode\":true", "\"account\":\"acct_other\",\"livemode\":false",
            "\"context\":\"acct_other\",\"livemode\":false"})
    void rejectsLiveConnectedAndOrganizationEvents(String replacement) {
        String body = body(NOW.getEpochSecond()).replace("\"livemode\":false", replacement);
        assertThrows(BadRequestException.class, () -> verifier.verify(bytes(body), sign(body, NOW.getEpochSecond())));
    }

    @Test
    void rejectsMissingSignatureWrongObjectAndInvalidJson() {
        String body = body(NOW.getEpochSecond());
        assertThrows(BadRequestException.class, () -> verifier.verify(bytes(body), null));
        String wrong = body.replace("\"id\":\"in_test\"", "\"id\":\"sub_test\"");
        assertThrows(BadRequestException.class, () -> verifier.verify(bytes(wrong), sign(wrong, NOW.getEpochSecond())));
        String invalid = "{broken";
        assertThrows(BadRequestException.class, () -> verifier.verify(bytes(invalid), sign(invalid, NOW.getEpochSecond())));
    }

    @Test
    void rejectsOversizedPayloadBeforeParsing() {
        var oversized = new byte[StripeWebhookVerifier.MAX_BODY_BYTES + 1];
        var error = assertThrows(ClientErrorException.class, () -> verifier.verify(oversized, "t=1,v1=bad"));
        assertEquals(413, error.getResponse().getStatus());
    }

    @Test
    void refusesDisabledOrUnconfiguredEndpoint() {
        assertThrows(NotFoundException.class, () -> new StripeWebhookVerifier(false, SECRET, Clock.systemUTC())
                .verify(bytes("{}"), null));
        assertThrows(ServiceUnavailableException.class, () -> new StripeWebhookVerifier(true, "", Clock.systemUTC())
                .verify(bytes("{}"), null));
    }

    @Test
    void ignoresUnsubscribedTypesOnlyAfterSignatureVerification() {
        String body = body(NOW.getEpochSecond()).replace("invoice.paid", "customer.updated");
        assertTrue(verifier.verify(bytes(body), sign(body, NOW.getEpochSecond())).isEmpty());
        assertThrows(BadRequestException.class, () -> verifier.verify(bytes(body), "t=1,v1=not_a_signature"));
    }

    @Test
    void synchronousResourceDoesNotAcknowledgeProcessingFailure() {
        var events = mock(ProcessSubscriptionEvents.class);
        var resource = new StripeWebhookResource(verifier, events);
        String body = body(NOW.getEpochSecond());
        when(events.process(any())).thenReturn(BillingEventResult.PROCESSED);
        assertEquals("PROCESSED", resource.receive(bytes(body), sign(body, NOW.getEpochSecond())).status());
        when(events.process(any())).thenThrow(new IllegalStateException("database failure"));
        assertThrows(IllegalStateException.class, () -> resource.receive(bytes(body), sign(body, NOW.getEpochSecond())));
    }

    static String body(long created) {
        return "{\"id\":\"evt_webhook_test\",\"object\":\"event\",\"created\":" + created
                + ",\"livemode\":false,\"type\":\"invoice.paid\","
                + "\"data\":{\"object\":{\"id\":\"in_test\",\"object\":\"invoice\"}}}";
    }

    static String sign(String body, long timestamp) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(bytes(SECRET), "HmacSHA256"));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(bytes(timestamp + "." + body)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
