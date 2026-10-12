package com.timereport.timeentry.adapter.in.rest;

import com.timereport.timeentry.application.service.ConflictException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ConflictMapper implements ExceptionMapper<ConflictException> {
    @Override
    public Response toResponse(ConflictException error) {
        return Response.status(409).type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.builder().code("CONFLICT").message(error.getMessage()).build()).build();
    }
}
