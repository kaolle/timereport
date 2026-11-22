package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.application.port.in.MaintainSubscriptions;
import com.timereport.subscription.domain.MaintenanceResult;
import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.ResponseHeader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Path("/internal/subscription/maintain")
public class SubscriptionMaintenanceResource {
    private final MaintainSubscriptions maintenance;
    private final String token;

    public SubscriptionMaintenanceResource(MaintainSubscriptions maintenance,
            @ConfigProperty(name = "subscription.maintenance-token") Optional<String> token) {
        this.maintenance = maintenance;
        this.token = token.orElse("");
    }

    @POST
    @Blocking
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Cache-Control", value = "no-store")
    public Response maintain(@HeaderParam("X-Subscription-Maintenance-Token") String supplied) {
        if (token.isBlank()) throw new NotFoundException();
        if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) throw new ForbiddenException();
        var result = maintenance.run();
        return Response.status(result.failed() == 0 ? 200 : 503).entity(MaintenanceResponse.from(result))
                .header("Cache-Control", "no-store").build();
    }

    @Builder
    public record MaintenanceResponse(int checked, int failed, int remindersQueued, int terminated) {
        static MaintenanceResponse from(MaintenanceResult result) {
            return new MaintenanceResponse(result.checked(), result.failed(), result.remindersQueued(), result.terminated());
        }
    }
}
