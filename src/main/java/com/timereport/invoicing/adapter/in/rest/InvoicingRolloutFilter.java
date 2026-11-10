package com.timereport.invoicing.adapter.in.rest;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.subscription.application.port.in.SubscriptionAccess;
import com.timereport.subscription.configuration.InvoicingRollout;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class InvoicingRolloutFilter implements ContainerRequestFilter {
    private final AccountIdentity identity;
    private final SubscriptionAccess subscriptions;
    private final InvoicingRollout rollout;

    public InvoicingRolloutFilter(AccountIdentity identity, SubscriptionAccess subscriptions,
                                 InvoicingRollout rollout) {
        this.identity = identity;
        this.subscriptions = subscriptions;
        this.rollout = rollout;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (!path.equals("api/invoicing") && !path.startsWith("api/invoicing/")) return;
        // Time registration uses this read-only label lookup without opening invoicing.
        if (path.equals("api/invoicing/customer-code-labels") && request.getMethod().equals("GET")) return;
        if (rollout.availableTo(subscriptions.status(identity.accountId()))) return;
        request.abortWith(Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .entity(InvoicingConflictResponse.builder().code("INVOICING_COMING_SOON")
                        .message("Fakturering kommer snart. Du kan fortsätta registrera tid som vanligt.").build())
                .build());
    }
}
