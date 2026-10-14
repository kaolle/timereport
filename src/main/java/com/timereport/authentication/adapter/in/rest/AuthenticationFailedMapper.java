package com.timereport.authentication.adapter.in.rest;

import com.timereport.authentication.application.service.AuthenticationFailed;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class AuthenticationFailedMapper implements ExceptionMapper<AuthenticationFailed> {
    @Override
    public Response toResponse(AuthenticationFailed failure) {
        return Response.status(401).header("WWW-Authenticate", "Bearer")
                .entity(new MessageResponse(failure.getMessage())).build();
    }
}
