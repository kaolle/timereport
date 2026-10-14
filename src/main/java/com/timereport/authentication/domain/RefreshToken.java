package com.timereport.authentication.domain;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record RefreshToken(String hash, UUID sessionId, boolean consumed) {
    public RefreshToken {
        java.util.Objects.requireNonNull(hash, "hash is required");
        java.util.Objects.requireNonNull(sessionId, "sessionId is required");
    }

    @Override
    public String toString() {
        return "RefreshToken[redacted]";
    }
}
