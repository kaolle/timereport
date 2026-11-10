package com.timereport.subscription.adapter.in.rest;

import lombok.Builder;

@Builder
public record SubscriptionBillingErrorResponse(String code, String message) {
}
