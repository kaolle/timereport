package com.timereport.subscription.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative renewal details, supplied while the account transaction is locked. */
@Builder(toBuilder = true)
public record RenewalReminderContext(UUID accountId, String subscriptionId, SubscriptionPlan plan,
                                     Instant renewalAt, BigDecimal amountIncludingVat, String recipientEmail,
                                     boolean renewing, boolean ownerAccess) {
    public RenewalReminderContext {
        Objects.requireNonNull(accountId, "Account is required");
        if (subscriptionId == null || !subscriptionId.startsWith("sub_"))
            throw new IllegalArgumentException("Subscription is required");
        if (renewing && !ownerAccess) {
            Objects.requireNonNull(plan, "Plan is required for renewal");
            Objects.requireNonNull(renewalAt, "Renewal date is required");
            Objects.requireNonNull(amountIncludingVat, "Renewal amount is required");
            if (recipientEmail == null || recipientEmail.isBlank())
                throw new IllegalArgumentException("Renewal recipient is required");
            amountIncludingVat = amountIncludingVat.setScale(2, RoundingMode.UNNECESSARY);
            if (amountIncludingVat.signum() < 0)
                throw new IllegalArgumentException("Renewal amount cannot be negative");
        }
    }

    @Override
    public String toString() {
        return "RenewalReminderContext[accountId=" + accountId + ", subscriptionId=" + subscriptionId + "]";
    }
}
