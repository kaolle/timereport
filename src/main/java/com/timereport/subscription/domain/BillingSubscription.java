package com.timereport.subscription.domain;

import lombok.Builder;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Builder
public record BillingSubscription(String id, String customerId, UUID accountId, UUID priceModelId,
                                  UUID checkoutAttemptId, String checkoutSessionId, String priceId,
                                  SubscriptionPlan plan, State state, Instant periodStart, Instant periodEnd,
                                  boolean cancelAtPeriodEnd, Instant endedAt, List<BillingInvoice> invoices) {
    public enum State { INCOMPLETE, ACTIVE, PAST_DUE, CANCELED, UNPAID, PAUSED }

    public BillingSubscription {
        Objects.requireNonNull(id);
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(state);
        if (!id.startsWith("sub_") || !customerId.startsWith("cus_"))
            throw new IllegalArgumentException("Invalid subscription references");
        invoices = invoices == null ? List.of() : List.copyOf(invoices);
        if (periodStart != null && periodEnd != null && !periodEnd.isAfter(periodStart))
            throw new IllegalArgumentException("Invalid subscription period");
    }
}
