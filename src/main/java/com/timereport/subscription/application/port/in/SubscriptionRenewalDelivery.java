package com.timereport.subscription.application.port.in;

import java.time.Instant;
import java.util.UUID;

public interface SubscriptionRenewalDelivery {
    void completed(UUID accountId, UUID mailId, String state, Instant completedAt);
}
