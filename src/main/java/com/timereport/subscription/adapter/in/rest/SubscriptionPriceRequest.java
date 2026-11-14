package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.domain.SubscriptionPlan;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

@Builder
public record SubscriptionPriceRequest(@NotNull UUID id, @NotBlank @Size(max = 100) String name,
                                       @NotNull SubscriptionPlan plan,
                                       @NotNull @DecimalMin("0.01") @DecimalMax("1000000")
                                       @Digits(integer = 7, fraction = 2) BigDecimal amount,
                                       @NotNull @DecimalMin("0") @DecimalMax("100")
                                       @Digits(integer = 3, fraction = 2) BigDecimal vatPercent,
                                       boolean vatInclusive) {
}
