package com.timereport.authentication.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Builder(toBuilder = true)
public record AccountToken(String hash, UUID accountId, String purpose, Instant expiresAt, boolean consumed) {
    public AccountToken {
        requireNonNull(hash, "hash is required");
        requireNonNull(accountId, "accountId is required");
        requireNonNull(purpose, "purpose is required");
        requireNonNull(expiresAt, "expiresAt is required");
        if (!purpose.equals("reset") && !purpose.equals("verify"))
            throw new IllegalArgumentException("Invalid token purpose");
    }

    @Override
    public String toString() {
        return "AccountToken[redacted]";
    }
}
