package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.application.service.SubscriptionRequired;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class SubscriptionRequiredMapper implements ExceptionMapper<SubscriptionRequired> {
    @Override
    public Response toResponse(SubscriptionRequired error) {
        return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .entity(InvoicingConflictResponse.builder().code("SUBSCRIPTION_REQUIRED")
                        .message(error.getMessage()).build()).build();
    }
}
