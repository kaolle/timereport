package com.timereport.mail.domain;

import lombok.Builder;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Builder
public record MailClaim(MailReference reference, MailMessage message, Instant expiresAt, int attempt, UUID leaseToken) {
    public MailClaim {
        Objects.requireNonNull(reference);
        Objects.requireNonNull(message);
        Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(leaseToken);
        if (attempt < 1) throw new IllegalArgumentException("Invalid mail attempt");
    }
}
