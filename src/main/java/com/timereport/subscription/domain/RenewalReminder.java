package com.timereport.subscription.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

@Builder(toBuilder = true)
public record RenewalReminder(UUID id, UUID accountId, String subscriptionId, SubscriptionPlan plan,
                              Instant renewalAt, Instant dueAt, BigDecimal amountIncludingVat,
                              State state, int generation, UUID mailId, Instant queuedAt, Instant completedAt) {
    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

    public enum State { PLANNED, QUEUED, SENT, FAILED, EXPIRED, CANCELLED }

    public RenewalReminder {
        Objects.requireNonNull(id);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(subscriptionId);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(renewalAt);
        Objects.requireNonNull(dueAt);
        Objects.requireNonNull(amountIncludingVat);
        Objects.requireNonNull(state);
        Objects.requireNonNull(mailId);
        amountIncludingVat = amountIncludingVat.setScale(2, RoundingMode.UNNECESSARY);
        if (!subscriptionId.startsWith("sub_") || amountIncludingVat.signum() < 0
                || !dueAt.isBefore(renewalAt) || generation < 0)
            throw new IllegalArgumentException("Invalid renewal reminder schedule");
    }

    public static Instant dueAt(SubscriptionPlan plan, Instant renewalAt) {
        var date = renewalAt.atZone(ZONE).toLocalDate();
        var due = plan == SubscriptionPlan.MONTHLY ? date.minusDays(7) : date.minusMonths(1);
        return due.atTime(LocalTime.of(9, 0)).atZone(ZONE).toInstant();
    }

    public static UUID idFor(RenewalReminderContext context) {
        return UUID.nameUUIDFromBytes(("subscription-renewal:" + context.subscriptionId() + ":"
                + context.renewalAt() + ":" + context.plan()).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID mailIdFor(UUID reminderId, int generation) {
        return UUID.nameUUIDFromBytes((reminderId + ":mail:" + generation).getBytes(StandardCharsets.UTF_8));
    }

    public boolean pending() {
        return state == State.PLANNED || state == State.QUEUED;
    }

    public boolean due(Instant now) {
        return state == State.PLANNED && !now.isBefore(dueAt) && now.isBefore(renewalAt);
    }
}
