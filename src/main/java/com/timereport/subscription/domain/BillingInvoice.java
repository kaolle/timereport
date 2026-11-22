package com.timereport.subscription.domain;

import lombok.Builder;
import java.time.Instant;
import java.util.Objects;

@Builder
public record BillingInvoice(String id, String subscriptionId, String customerId, String priceId,
                             State state, Instant periodStart, Instant periodEnd, long amountPaid,
                             long total, Instant paidAt, boolean renewal) {
    public enum State { DRAFT, OPEN, PAID, VOID, UNCOLLECTIBLE }

    public BillingInvoice {
        Objects.requireNonNull(id);
        Objects.requireNonNull(subscriptionId);
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(state);
        if (!id.startsWith("in_") || !subscriptionId.startsWith("sub_") || !customerId.startsWith("cus_"))
            throw new IllegalArgumentException("Invalid billing invoice references");
        if (state == State.PAID && (priceId == null || periodStart == null || periodEnd == null
                || !periodEnd.isAfter(periodStart) || paidAt == null || amountPaid < 0))
            throw new IllegalArgumentException("Paid invoice requires a verified service period");
    }
}
