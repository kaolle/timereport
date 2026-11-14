package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.application.service.SubscriptionPriceNotFound;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class SubscriptionPriceNotFoundMapper implements ExceptionMapper<SubscriptionPriceNotFound> {
    @Override
    public Response toResponse(SubscriptionPriceNotFound error) {
        return Response.status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .entity(SubscriptionBillingErrorResponse.builder().code("PRICE_NOT_FOUND")
                        .message(error.getMessage()).build()).build();
    }
}
