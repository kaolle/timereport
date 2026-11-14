package com.timereport.subscription.application.port.out;

import com.timereport.subscription.domain.StripePriceReferences;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPriceRepository {
    List<SubscriptionPriceModel> list();

    SubscriptionPriceModel reserve(SubscriptionPriceModel draft);

    void complete(UUID id, StripePriceReferences references);

    SubscriptionPriceModel activate(UUID id);

    Optional<SubscriptionPriceModel> find(UUID id);

    SubscriptionPriceModel forCheckout(UUID accountId, UUID attemptId, SubscriptionPlan plan);
}
