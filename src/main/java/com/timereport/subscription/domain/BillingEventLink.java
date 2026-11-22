package com.timereport.subscription.domain;

import lombok.Builder;

@Builder
public record BillingEventLink(String customerId, String subscriptionId, String reviewKind) {}
