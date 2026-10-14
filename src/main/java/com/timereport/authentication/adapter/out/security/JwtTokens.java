package com.timereport.authentication.adapter.out.security;

import com.timereport.authentication.application.port.out.Tokens;
import com.timereport.authentication.domain.Account;
import com.timereport.authentication.domain.Session;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@ApplicationScoped
public class JwtTokens implements Tokens {
    private final SecureRandom random = new SecureRandom();
    @ConfigProperty(name = "auth.issuer")
    String issuer;

    @Override
    public String random() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String digest(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String access(Account account, Session session) {
        return Jwt.issuer(issuer).audience("timereport").subject(account.id().toString())
                .groups(Set.of("user")).claim("account_id", account.id().toString())
                .claim("sid", session.id().toString()).expiresIn(accessLifetimeSeconds()).sign();
    }

    @Override
    public long accessLifetimeSeconds() {
        return 300;
    }
}
