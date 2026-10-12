package com.timereport.timeentry.adapter.in.rest;

import com.timereport.timeentry.application.service.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class NotFoundMapper implements ExceptionMapper<NotFoundException> {
    @Override
    public Response toResponse(NotFoundException error) {
        return Response.status(404).type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse.builder().code("NOT_FOUND").message(error.getMessage()).build()).build();
    }
}
