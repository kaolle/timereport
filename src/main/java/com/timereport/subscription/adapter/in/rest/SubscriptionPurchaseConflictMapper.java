package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.application.service.SubscriptionPurchaseConflict;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class SubscriptionPurchaseConflictMapper implements ExceptionMapper<SubscriptionPurchaseConflict> {
    @Override
    public Response toResponse(SubscriptionPurchaseConflict error) {
        return Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .entity(SubscriptionBillingErrorResponse.builder().code("SUBSCRIPTION_CONFLICT")
                        .message(error.getMessage()).build()).build();
    }
}
