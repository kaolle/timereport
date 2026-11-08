package com.timereport.subscription.application.port.in;

import com.timereport.subscription.domain.SubscriptionSnapshot;

import java.util.UUID;

public interface SubscriptionAccess {
    SubscriptionSnapshot status(UUID accountId);
}
