package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class SubscriptionBillingUnavailableMapper implements ExceptionMapper<SubscriptionBillingUnavailable> {
    @Override
    public Response toResponse(SubscriptionBillingUnavailable error) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .entity(SubscriptionBillingErrorResponse.builder().code("BILLING_UNAVAILABLE")
                        .message(error.getMessage()).build()).build();
    }
}
