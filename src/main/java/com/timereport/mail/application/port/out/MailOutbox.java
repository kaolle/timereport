package com.timereport.mail.application.port.out;

import com.timereport.mail.domain.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MailOutbox {
    void enqueue(MailReference reference, MailMessage message, Instant now, Instant expiresAt);
    void cancel(UUID accountId, String type, Instant now);
    Optional<MailClaim> claim(Instant now, Instant leaseUntil);
    boolean finish(MailClaim claim, MailState state, Instant now, Instant nextAttemptAt, String errorCode);
    int deleteCompletedBefore(Instant cutoff);
}
