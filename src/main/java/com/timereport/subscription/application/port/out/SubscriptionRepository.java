package com.timereport.subscription.application.port.out;

import com.timereport.subscription.domain.SubscriptionSnapshot;

import java.util.UUID;

public interface SubscriptionRepository {
    SubscriptionSnapshot status(UUID accountId);
}
