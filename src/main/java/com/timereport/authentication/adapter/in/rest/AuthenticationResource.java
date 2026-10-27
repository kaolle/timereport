package com.timereport.authentication.adapter.in.rest;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.authentication.application.port.in.Authentication;
import com.timereport.authentication.application.port.out.AuthRepository;
import com.timereport.authentication.domain.AuthTokens;
import io.vertx.core.http.HttpServerRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Locale;

@Path("/api/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthenticationResource {
    private final Authentication auth;
    private final AccountIdentity identity;
    private final AuthRepository repository;
    private final AuthRateLimiter limiter;
    private final HttpServerRequest http;
    @ConfigProperty(name = "auth.public-url")
    String publicUrl;
    @ConfigProperty(name = "auth.cookie-secure", defaultValue = "true")
    boolean secure;
    @ConfigProperty(name = "auth.ip-limit", defaultValue = "120")
    int ipLimit;
    @ConfigProperty(name = "auth.email-verification-required", defaultValue = "true")
    boolean emailVerificationRequired;

    public AuthenticationResource(Authentication auth, AccountIdentity identity, AuthRepository repository,
                                  AuthRateLimiter limiter, HttpServerRequest http) {
        this.auth = auth;
        this.identity = identity;
        this.repository = repository;
        this.limiter = limiter;
        this.http = http;
    }

    private void guard(String email) {
        String origin = http.getHeader("Origin");
        if (!"timereport".equals(http.getHeader("X-Requested-With"))
                || (origin != null && !origin.equals(publicUrl))) {
            throw new ForbiddenException("Begäran måste komma från Tidrapporten.");
        }
        // Use the direct peer address; never trust arbitrary client X-Forwarded-For.
        if (!limiter.allow("ip:" + http.remoteAddress().host(), ipLimit)
                || (email != null && !limiter.allow("account:" + email.strip().toLowerCase(Locale.ROOT), 10))) {
            throw new WebApplicationException(Response.status(429).header("Retry-After", "900")
                    .entity(new MessageResponse("För många försök. Försök igen om 15 minuter.")).build());
        }
    }

    @POST
    @Path("/register")
    public Response register(@NotNull @Valid RegistrationRequest request) {
        guard(request.email());
        auth.register(request.email(), request.password());
        if (!emailVerificationRequired) return loggedIn(auth.login(request.email(), request.password()));
        return accepted();
    }

    @GET
    @Path("/config")
    public AuthConfigResponse config() {
        return new AuthConfigResponse(emailVerificationRequired);
    }

    @POST
    @Path("/verify-email")
    public Response verify(@NotNull @Valid TokenPasswordRequest request) {
        guard(null);
        auth.verifyEmail(request.token(), request.password());
        return Response.noContent().build();
    }

    @POST
    @Path("/login")
    public Response login(@NotNull @Valid CredentialsRequest request) {
        guard(request.email());
        return loggedIn(auth.login(request.email(), request.password()));
    }

    @POST
    @Path("/refresh")
    @Consumes(MediaType.WILDCARD)
    public Response refresh(@CookieParam("timereport_refresh") String refresh) {
        guard(null);
        return loggedIn(auth.refresh(refresh));
    }

    @POST
    @Path("/logout")
    @Consumes(MediaType.WILDCARD)
    public Response logout(@CookieParam("timereport_refresh") String refresh) {
        guard(null);
        auth.logout(refresh);
        return Response.noContent().header("Set-Cookie", cookie("", 0)).build();
    }

    @POST
    @Path("/forgot-password")
    public Response forgot(@NotNull @Valid EmailRequest request) {
        guard(request.email());
        auth.requestReset(request.email());
        return accepted();
    }

    @POST
    @Path("/reset-password")
    public Response reset(@NotNull @Valid TokenPasswordRequest request) {
        guard(null);
        auth.resetPassword(request.token(), request.password());
        return Response.noContent().header("Set-Cookie", cookie("", 0)).build();
    }

    @POST
    @Path("/change-password")
    public Response change(@NotNull @Valid ChangePasswordRequest request) {
        guard(null);
        auth.changePassword(identity.accountId(), request.currentPassword(), request.newPassword());
        return Response.noContent().header("Set-Cookie", cookie("", 0)).build();
    }

    @GET
    @Path("/me")
    public AccountResponse me() {
        var account = repository.account(identity.accountId()).orElseThrow(com.timereport.authentication.application.service.AuthenticationFailed::new);
        return new AccountResponse(account.id(), account.email());
    }

    private Response accepted() {
        return Response.accepted(new MessageResponse("Om adressen kan användas skickas ett mejl med nästa steg.")).build();
    }

    private Response loggedIn(AuthTokens tokens) {
        return Response.ok(LoginResponse.builder().accessToken(tokens.accessToken()).tokenType("Bearer")
                        .expiresIn(tokens.expiresIn()).account(new AccountResponse(tokens.account().id(), tokens.account().email())).build())
                .header("Set-Cookie", cookie(tokens.refreshToken(), Math.max(0, tokens.refreshExpiresAt().getEpochSecond() - Instant.now().getEpochSecond())))
                .build();
    }

    private String cookie(String value, long maxAge) {
        return "timereport_refresh=" + value + "; Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age=" + maxAge + (secure ? "; Secure" : "");
    }
}
