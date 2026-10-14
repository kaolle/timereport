package com.timereport.authentication.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
public record Session(UUID id, UUID accountId, Instant expiresAt, boolean revoked) {
    public Session {
        java.util.Objects.requireNonNull(id, "id is required");
        java.util.Objects.requireNonNull(accountId, "accountId is required");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt is required");
    }

    @Override
    public String toString() {
        return "Session[redacted]";
    }
}
