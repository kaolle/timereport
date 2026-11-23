package com.timereport.appearance.adapter.in.rest;

import com.timereport.appearance.application.port.in.AppearanceSettings;
import com.timereport.appearance.domain.Appearance;
import com.timereport.authentication.application.port.in.AccountAuthorization;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseHeader;

@Path("/api/admin/appearance")
@RolesAllowed("admin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AppearanceAdminResource {
    private final AppearanceSettings settings;
    private final AccountAuthorization authorization;

    public AppearanceAdminResource(AppearanceSettings settings, AccountAuthorization authorization) {
        this.settings = settings;
        this.authorization = authorization;
    }

    @POST
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public AppearanceResponse change(@NotNull @Valid AppearanceRequest request) {
        authorization.requireAdmin();
        return AppearanceResponse.from(settings.change(new Appearance(request.theme())));
    }
}
