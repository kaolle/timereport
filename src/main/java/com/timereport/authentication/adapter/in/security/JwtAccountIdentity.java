package com.timereport.authentication.adapter.in.security;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.authentication.application.port.out.AuthRepository;
import com.timereport.authentication.application.service.AuthenticationFailed;
import jakarta.enterprise.context.RequestScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.UUID;

@RequestScoped
public class JwtAccountIdentity implements AccountIdentity {
    private final JsonWebToken jwt;
    private final AuthRepository repository;
    private UUID accountId;
    private UUID sessionId;

    public JwtAccountIdentity(JsonWebToken jwt, AuthRepository repository) {
        this.jwt = jwt;
        this.repository = repository;
    }

    private void validate() {
        if (accountId != null) return;
        try {
            UUID subject = UUID.fromString(jwt.getSubject());
            UUID sid = UUID.fromString(jwt.getClaim("sid"));
            var session = repository.session(sid).orElseThrow(AuthenticationFailed::new);
            if (session.revoked() || !session.expiresAt().isAfter(Instant.now()) || !subject.equals(session.accountId())
                    || !subject.toString().equals(jwt.getClaim("account_id"))) throw new AuthenticationFailed();
            accountId = subject;
            sessionId = sid;
        } catch (IllegalArgumentException | NullPointerException | ClassCastException e) {
            throw new AuthenticationFailed();
        }
    }

    @Override
    public UUID accountId() {
        validate();
        return accountId;
    }

    @Override
    public UUID sessionId() {
        validate();
        return sessionId;
    }
}
