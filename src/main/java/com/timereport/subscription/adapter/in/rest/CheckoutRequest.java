package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.domain.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record CheckoutRequest(@NotNull SubscriptionPlan plan) {
}
