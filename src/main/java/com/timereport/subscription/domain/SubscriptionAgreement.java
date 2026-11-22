package com.timereport.subscription.domain;

import lombok.Builder;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Builder(toBuilder = true)
public record SubscriptionAgreement(UUID id, UUID accountId, String subscriptionId, UUID priceModelId,
                                    SubscriptionPlan plan, SubscriptionState state, Instant graceUntil,
                                    boolean cancelAtPeriodEnd, Instant periodStart, Instant periodEnd,
                                    Instant endedAt, String terminalReason, Instant updatedAt) {
    public SubscriptionAgreement {
        Objects.requireNonNull(id);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(subscriptionId);
        Objects.requireNonNull(state);
        Objects.requireNonNull(updatedAt);
        if (terminalReason != null && (state != SubscriptionState.CANCELED || endedAt == null))
            throw new IllegalArgumentException("Closed agreements must retain a terminal state");
    }

    public boolean terminal() {
        return terminalReason != null;
    }
}
