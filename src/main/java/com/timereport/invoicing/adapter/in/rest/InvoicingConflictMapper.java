package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.application.service.InvoicingConflict;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class InvoicingConflictMapper implements ExceptionMapper<InvoicingConflict> {
    @Override public Response toResponse(InvoicingConflict error) {
        return Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON)
                .entity(InvoicingConflictResponse.builder().code("CONFLICT").message(error.getMessage()).build()).build();
    }
}
