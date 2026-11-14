package com.timereport.subscription.adapter.in.rest;

import com.timereport.authentication.application.port.in.AccountAuthorization;
import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.subscription.application.port.in.SubscriptionPricing;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseHeader;

import java.util.List;
import java.util.UUID;

@Path("/api/admin/subscription/prices")
@RolesAllowed("admin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SubscriptionPriceAdminResource {
    private final SubscriptionPricing pricing;
    private final AccountAuthorization authorization;
    private final AccountIdentity identity;

    public SubscriptionPriceAdminResource(SubscriptionPricing pricing, AccountAuthorization authorization,
                                          AccountIdentity identity) {
        this.pricing = pricing;
        this.authorization = authorization;
        this.identity = identity;
    }

    @GET
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public List<SubscriptionPriceResponse> list() {
        authorization.requireAdmin();
        return pricing.list().stream().map(SubscriptionPriceResponse::from).toList();
    }

    @POST
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public SubscriptionPriceResponse create(@NotNull @Valid SubscriptionPriceRequest request) {
        authorization.requireAdmin();
        return SubscriptionPriceResponse.from(pricing.create(identity.accountId(), request.id(), request.name(),
                request.plan(), request.amount(), request.vatPercent(), request.vatInclusive()));
    }

    @POST
    @Path("/{id}/activate")
    @Consumes(MediaType.WILDCARD)
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public SubscriptionPriceResponse activate(@PathParam("id") UUID id) {
        authorization.requireAdmin();
        return SubscriptionPriceResponse.from(pricing.activate(id));
    }
}
