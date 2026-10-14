package com.timereport.authentication.domain;

import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record AuthTokens(Account account, String accessToken, String refreshToken, Instant refreshExpiresAt,
                         long expiresIn) {
    public AuthTokens {
        java.util.Objects.requireNonNull(account, "account is required");
        java.util.Objects.requireNonNull(accessToken, "accessToken is required");
        java.util.Objects.requireNonNull(refreshToken, "refreshToken is required");
        java.util.Objects.requireNonNull(refreshExpiresAt, "refreshExpiresAt is required");
        if (expiresIn < 1) throw new IllegalArgumentException("Access token lifetime must be positive");
    }

    @Override
    public String toString() {
        return "AuthTokens[redacted]";
    }
}
