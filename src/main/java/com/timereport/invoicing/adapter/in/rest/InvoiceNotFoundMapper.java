package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.application.service.InvoiceNotFound;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class InvoiceNotFoundMapper implements ExceptionMapper<InvoiceNotFound> {
    @Override
    public Response toResponse(InvoiceNotFound error) {
        return Response.status(404).entity(Map.of("message", error.getMessage())).type("application/json").build();
    }
}
