package com.timereport.subscription.application.port.in;

import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface SubscriptionPricing {
    List<SubscriptionPriceModel> list();

    SubscriptionPriceModel create(UUID actorId, UUID requestId, String name, SubscriptionPlan plan,
                                  BigDecimal amount, BigDecimal vatPercent, boolean vatInclusive);

    SubscriptionPriceModel activate(UUID id);
}
