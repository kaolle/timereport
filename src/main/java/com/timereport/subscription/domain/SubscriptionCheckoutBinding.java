package com.timereport.subscription.domain;

import lombok.Builder;
import java.util.UUID;

@Builder
public record SubscriptionCheckoutBinding(UUID accountId, UUID attemptId, String sessionId,
                                          UUID priceModelId, String priceId, boolean catalogPricing) {}
