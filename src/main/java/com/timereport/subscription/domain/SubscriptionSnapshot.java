package com.timereport.subscription.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Builder
public record SubscriptionSnapshot(UUID accountId, SubscriptionPlan plan, SubscriptionState state,
                                   Instant paidUntil, Instant graceUntil, boolean cancelAtPeriodEnd,
                                   boolean ownerAccess) {
    public SubscriptionSnapshot {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(state, "state is required");
    }

    public boolean canInvoiceAt(Instant now) {
        Objects.requireNonNull(now, "now is required");
        return ownerAccess || paidUntil != null && now.isBefore(paidUntil)
                || paidUntil != null && state == SubscriptionState.PAST_DUE
                && graceUntil != null && now.isBefore(graceUntil);
    }
}
