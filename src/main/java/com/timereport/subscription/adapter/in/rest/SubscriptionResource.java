package com.timereport.subscription.adapter.in.rest;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.subscription.application.port.in.SubscriptionAccess;
import com.timereport.subscription.application.port.in.SubscriptionPurchases;
import com.timereport.subscription.configuration.InvoicingRollout;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.resteasy.reactive.ResponseHeader;

import java.time.Instant;

@Path("/api/subscription")
public class SubscriptionResource {
    private final SubscriptionAccess subscriptions;
    private final SubscriptionPurchases purchases;
    private final AccountIdentity identity;
    private final InvoicingRollout rollout;

    public SubscriptionResource(SubscriptionAccess subscriptions, SubscriptionPurchases purchases,
                                AccountIdentity identity, InvoicingRollout rollout) {
        this.subscriptions = subscriptions;
        this.purchases = purchases;
        this.identity = identity;
        this.rollout = rollout;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public SubscriptionStatusResponse status() {
        var snapshot = subscriptions.status(identity.accountId());
        return SubscriptionStatusResponse.from(snapshot, Instant.now(), rollout.availableTo(snapshot));
    }

    @POST
    @Path("/checkout")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public CheckoutResponse checkout(@NotNull @Valid CheckoutRequest request) {
        requireRolloutAccess();
        var hosted = purchases.checkout(identity.accountId(), request.plan());
        return CheckoutResponse.builder().url(hosted.url()).expiresAt(hosted.expiresAt()).build();
    }

    @POST
    @Path("/portal")
    @Produces(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public PortalResponse portal() {
        requireRolloutAccess();
        return PortalResponse.builder().url(purchases.portal(identity.accountId())).build();
    }

    private void requireRolloutAccess() {
        if (rollout.availableTo(subscriptions.status(identity.accountId()))) return;
        throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store")
                .entity(SubscriptionBillingErrorResponse.builder().code("INVOICING_COMING_SOON")
                        .message("Fakturering kommer snart.").build()).build());
    }
}
