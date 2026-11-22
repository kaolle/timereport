package com.timereport.subscription.adapter.in.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import com.timereport.subscription.domain.BillingEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class StripeWebhookVerifier {
    static final int MAX_BODY_BYTES = 65_536;
    static final long SIGNATURE_TOLERANCE_SECONDS = 300;
    private static final Set<String> SUPPORTED = Set.of(
            "checkout.session.completed", "checkout.session.async_payment_succeeded",
            "checkout.session.async_payment_failed", "checkout.session.expired",
            "invoice.paid", "invoice.payment_succeeded", "invoice.payment_failed",
            "invoice.payment_action_required", "invoice.voided", "invoice.marked_uncollectible",
            "invoice.finalized", "customer.subscription.created", "customer.subscription.updated",
            "customer.subscription.deleted", "customer.subscription.paused", "customer.subscription.resumed",
            "charge.refunded", "charge.dispute.created", "charge.dispute.updated", "charge.dispute.closed");
    private final boolean enabled;
    private final String secret;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Inject
    public StripeWebhookVerifier(@ConfigProperty(name = "subscription.webhooks-enabled") boolean enabled,
                                 @ConfigProperty(name = "subscription.stripe.webhook-secret") Optional<String> secret) {
        this(enabled, secret.orElse(""), Clock.systemUTC());
    }

    StripeWebhookVerifier(boolean enabled, String secret, Clock clock) {
        this.enabled = enabled;
        this.secret = secret;
        this.clock = clock;
    }

    public Optional<BillingEvent> verify(byte[] body, String signature) {
        if (!enabled) throw new NotFoundException();
        if (secret == null || !secret.startsWith("whsec_") || secret.length() <= 6)
            throw new ServiceUnavailableException("Stripe-webhook är inte konfigurerad.");
        if (body == null || body.length == 0) throw invalid();
        if (body.length > MAX_BODY_BYTES) throw new ClientErrorException(413);
        if (signature == null || signature.isBlank() || signature.length() > 4_096) throw invalid();
        try {
            String payload = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            // Verify the original text before inspecting any of its contents.
            var signed = Webhook.constructEvent(payload, signature, secret, SIGNATURE_TOLERANCE_SECONDS, clock);
            checkFutureTimestamp(signature);
            if (signed == null || signed.getId() == null || !signed.getId().startsWith("evt_")
                    || signed.getType() == null || signed.getCreated() == null || signed.getCreated() <= 0
                    || !Boolean.FALSE.equals(signed.getLivemode())
                    || signed.getAccount() != null || signed.getContext() != null) throw invalid();
            if (!SUPPORTED.contains(signed.getType())) return Optional.empty();
            var object = json.readTree(payload).path("data").path("object");
            String objectId = object.path("id").asText();
            if (!matchesObject(signed.getType(), object.path("object").asText(), objectId)) throw invalid();
            return Optional.of(BillingEvent.builder().id(signed.getId()).type(signed.getType()).objectId(objectId)
                    .createdAt(Instant.ofEpochSecond(signed.getCreated())).build());
        } catch (SignatureVerificationException | CharacterCodingException | JsonProcessingException | RuntimeException invalid) {
            throw invalid();
        }
    }

    private void checkFutureTimestamp(String signature) {
        Long timestamp = null;
        for (String part : signature.split(",")) {
            if (!part.startsWith("t=")) continue;
            if (timestamp != null) throw invalid();
            timestamp = Long.parseLong(part.substring(2));
        }
        // The SDK rejects old signatures; also reject signatures from the future.
        if (timestamp == null || timestamp > clock.instant().getEpochSecond() + SIGNATURE_TOLERANCE_SECONDS)
            throw invalid();
    }

    private boolean matchesObject(String eventType, String objectType, String id) {
        if (eventType.startsWith("checkout.session."))
            return "checkout.session".equals(objectType) && id.startsWith("cs_test_");
        if (eventType.startsWith("invoice.")) return "invoice".equals(objectType) && id.startsWith("in_");
        if (eventType.startsWith("customer.subscription."))
            return "subscription".equals(objectType) && id.startsWith("sub_");
        if (eventType.startsWith("charge.dispute.")) return "dispute".equals(objectType) && id.startsWith("dp_");
        return "charge".equals(objectType) && id.startsWith("ch_");
    }

    private BadRequestException invalid() {
        return new BadRequestException("Ogiltig Stripe-händelse eller signatur.");
    }
}
