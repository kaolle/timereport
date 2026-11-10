package com.timereport.subscription.application.port.in;

import com.timereport.subscription.domain.HostedCheckout;
import com.timereport.subscription.domain.SubscriptionPlan;

import java.util.UUID;

public interface SubscriptionPurchases {
    HostedCheckout checkout(UUID accountId, SubscriptionPlan plan);

    String portal(UUID accountId);
}
