package com.timereport.subscription.adapter.in.rest;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.subscription.application.port.in.SubscriptionAccess;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseHeader;

import java.time.Instant;

@Path("/api/subscription")
public class SubscriptionResource {
    private final SubscriptionAccess subscriptions;
    private final AccountIdentity identity;

    public SubscriptionResource(SubscriptionAccess subscriptions, AccountIdentity identity) {
        this.subscriptions = subscriptions;
        this.identity = identity;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public SubscriptionStatusResponse status() {
        return SubscriptionStatusResponse.from(subscriptions.status(identity.accountId()), Instant.now());
    }
}
