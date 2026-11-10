package com.timereport.subscription.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.Objects;

@Builder
public record HostedCheckout(String sessionId, String customerId, String url,
                             Instant expiresAt, State state) {
    public enum State { OPEN, COMPLETE, EXPIRED }

    public HostedCheckout {
        Objects.requireNonNull(sessionId, "sessionId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        Objects.requireNonNull(state, "state is required");
        if (state == State.OPEN && (url == null || !url.startsWith("https://")))
            throw new IllegalArgumentException("An open checkout needs an HTTPS URL");
    }
}
