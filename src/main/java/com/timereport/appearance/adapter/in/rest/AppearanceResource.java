package com.timereport.appearance.adapter.in.rest;

import com.timereport.appearance.application.port.in.AppearanceSettings;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseHeader;

@Path("/api/appearance")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
public class AppearanceResource {
    private final AppearanceSettings settings;

    public AppearanceResource(AppearanceSettings settings) { this.settings = settings; }

    @GET
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public AppearanceResponse current() { return AppearanceResponse.from(settings.current()); }
}
