package com.timereport.mail.domain;

import lombok.Builder;
import java.time.Duration;

@Builder
public record RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay) {
    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 30 || initialDelay.isNegative() || initialDelay.isZero()
                || maxDelay.compareTo(initialDelay) < 0) throw new IllegalArgumentException("Invalid mail retry policy");
    }

    public Duration delayAfter(int attempt) {
        return Duration.ofSeconds(Math.min(maxDelay.toSeconds(), initialDelay.toSeconds() * (1L << Math.min(29, Math.max(0, attempt - 1)))));
    }
}
