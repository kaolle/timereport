package com.timereport.subscription.application.port.out;

import com.timereport.subscription.domain.CheckoutAttempt;
import com.timereport.subscription.domain.SubscriptionPlan;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPurchaseRepository {
    CheckoutAttempt reserve(UUID accountId, SubscriptionPlan plan, Instant now);

    Optional<String> customerId(UUID accountId);

    String customerIdempotencyKey(UUID accountId);

    String saveCustomerId(UUID accountId, String stripeCustomerId);

    void open(UUID attemptId, String stripeSessionId, Instant expiresAt);

    void complete(UUID attemptId);

    void expire(UUID attemptId);
}
