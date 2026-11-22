package com.timereport.subscription.domain;

import lombok.Builder;
import java.time.Instant;
import java.util.Objects;

@Builder
public record BillingEvent(String id, String type, String objectId, Instant createdAt) {
    public BillingEvent {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(objectId);
        Objects.requireNonNull(createdAt);
        if (!id.startsWith("evt_") || id.length() > 255 || type.length() > 100 || objectId.length() > 255)
            throw new IllegalArgumentException("Invalid billing event");
    }
}
