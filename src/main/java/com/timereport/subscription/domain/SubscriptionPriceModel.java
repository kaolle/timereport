package com.timereport.subscription.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Builder(toBuilder = true)
public record SubscriptionPriceModel(UUID id, String name, SubscriptionPlan plan, BigDecimal amount,
                                     BigDecimal vatPercent, boolean vatInclusive, State state,
                                     StripePriceReferences stripe, boolean active, UUID createdBy, Instant createdAt) {
    public enum State { CREATING, READY }

    public SubscriptionPriceModel {
        Objects.requireNonNull(id, "Prisets id krävs.");
        Objects.requireNonNull(plan, "Välj månad eller år.");
        Objects.requireNonNull(state, "Prisets status krävs.");
        Objects.requireNonNull(createdBy, "Administratörens konto krävs.");
        Objects.requireNonNull(createdAt, "Skapandedatum krävs.");
        if (name == null || name.isBlank() || name.strip().length() > 100)
            throw new IllegalArgumentException("Ange ett prisnamn med högst 100 tecken.");
        name = name.strip();
        amount = decimal(amount, "Ange ett pris i SEK med högst två decimaler.");
        vatPercent = decimal(vatPercent, "Ange momssatsen med högst två decimaler.");
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("1000000")) > 0)
            throw new IllegalArgumentException("Priset måste vara mellan 0,01 och 1 000 000 SEK.");
        if (vatPercent.signum() < 0 || vatPercent.compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("Momssatsen måste vara mellan 0 och 100 procent.");
        if ((state == State.READY) != (stripe != null) || active && state != State.READY)
            throw new IllegalArgumentException("Endast färdiga Stripe-priser kan aktiveras.");
    }

    private static BigDecimal decimal(BigDecimal value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException(message); }
    }

    public long amountMinor() {
        return amount.movePointRight(2).longValueExact();
    }
}
