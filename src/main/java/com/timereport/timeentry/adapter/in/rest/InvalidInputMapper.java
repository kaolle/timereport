package com.timereport.timeentry.adapter.in.rest;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class InvalidInputMapper implements ExceptionMapper<IllegalArgumentException> {
    @Override
    public Response toResponse(IllegalArgumentException error) {
        return Response.status(400).type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.builder().code("INVALID_INPUT").message(error.getMessage()).build()).build();
    }
}
