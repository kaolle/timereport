package com.timereport.mail.adapter.in.rest;

import com.timereport.mail.application.port.in.DispatchMail;
import com.timereport.mail.application.port.in.CleanMailOutbox;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.smallrye.common.annotation.Blocking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import lombok.Builder;

@Path("/internal/mail")
public class MailDispatchResource {
    private final DispatchMail dispatcher;
    private final CleanMailOutbox cleaner;
    private final String token;

    public MailDispatchResource(DispatchMail dispatcher, CleanMailOutbox cleaner,
                                @ConfigProperty(name = "mail.dispatch-token") Optional<String> token) {
        this.dispatcher = dispatcher;
        this.cleaner = cleaner;
        this.token = token.orElse("");
    }

    @POST
    @Path("dispatch")
    @Blocking
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public DispatchResponse dispatch(@HeaderParam("X-Mail-Dispatch-Token") String supplied) {
        requireWorkerToken(supplied);
        return new DispatchResponse(dispatcher.dispatch());
    }

    @POST
    @Path("cleanup")
    @Blocking
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public CleanupResponse cleanup(@HeaderParam("X-Mail-Dispatch-Token") String supplied) {
        requireWorkerToken(supplied);
        return new CleanupResponse(cleaner.clean());
    }

    private void requireWorkerToken(String supplied) {
        if (token.isBlank()) throw new NotFoundException();
        if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) throw new ForbiddenException();
    }

    @Builder
    public record DispatchResponse(int processed) {}

    @Builder
    public record CleanupResponse(int deleted) {}
}
