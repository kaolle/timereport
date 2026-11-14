package com.timereport.authentication.adapter.in.rest;

import com.timereport.authentication.application.service.AdministrationForbidden;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class AdministrationForbiddenMapper implements ExceptionMapper<AdministrationForbidden> {
    @Override
    public Response toResponse(AdministrationForbidden failure) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(MessageResponse.builder().message(failure.getMessage()).build()).build();
    }
}
