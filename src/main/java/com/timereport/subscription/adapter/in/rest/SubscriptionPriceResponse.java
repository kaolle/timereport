package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder
public record SubscriptionPriceResponse(UUID id, String name, SubscriptionPlan plan, BigDecimal amount,
                                        BigDecimal vatPercent, boolean vatInclusive, SubscriptionPriceModel.State state,
                                        boolean active, Instant createdAt, String stripeProductId,
                                        String stripePriceId, String stripeTaxRateId) {
    public static SubscriptionPriceResponse from(SubscriptionPriceModel model) {
        var stripe = model.stripe();
        return SubscriptionPriceResponse.builder().id(model.id()).name(model.name()).plan(model.plan())
                .amount(model.amount()).vatPercent(model.vatPercent()).vatInclusive(model.vatInclusive())
                .state(model.state()).active(model.active()).createdAt(model.createdAt())
                .stripeProductId(stripe == null ? null : stripe.productId())
                .stripePriceId(stripe == null ? null : stripe.priceId())
                .stripeTaxRateId(stripe == null ? null : stripe.taxRateId()).build();
    }
}
