package com.timereport.subscription.adapter.in.rest;

import lombok.Builder;

import java.time.Instant;

@Builder
public record CheckoutResponse(String url, Instant expiresAt) {
}
