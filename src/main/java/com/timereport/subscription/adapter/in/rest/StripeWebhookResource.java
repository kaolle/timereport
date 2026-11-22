package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.application.port.in.ProcessSubscriptionEvents;
import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.Builder;
import org.jboss.resteasy.reactive.ResponseHeader;

@Path("/api/subscription/webhooks/stripe")
public class StripeWebhookResource {
    private final StripeWebhookVerifier verifier;
    private final ProcessSubscriptionEvents events;

    public StripeWebhookResource(StripeWebhookVerifier verifier, ProcessSubscriptionEvents events) {
        this.verifier = verifier;
        this.events = events;
    }

    @POST
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public StripeWebhookResponse receive(byte[] body, @HeaderParam("Stripe-Signature") String signature) {
        var event = verifier.verify(body, signature);
        // A successful response is sent only after synchronous durable processing returns.
        String result = event.map(value -> events.process(value).name()).orElse("IGNORED");
        return StripeWebhookResponse.builder().status(result).build();
    }

    @Builder
    public record StripeWebhookResponse(String status) {}
}
