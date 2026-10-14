package com.timereport.authentication.adapter.in.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
public class SecurityHeaders implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("Cache-Control", "no-store");
        response.getHeaders().putSingle("Pragma", "no-cache");
        response.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        response.getHeaders().putSingle("Referrer-Policy", "no-referrer");
    }
}
