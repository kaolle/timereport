package com.timereport.subscription.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Builder
public record CheckoutAttempt(UUID id, UUID accountId, SubscriptionPlan plan, State state,
                              String idempotencyKey, String stripeSessionId, Instant expiresAt) {
    public enum State { CREATING, OPEN, COMPLETED, EXPIRED }

    public CheckoutAttempt {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(plan, "plan is required");
        Objects.requireNonNull(state, "state is required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
    }
}
