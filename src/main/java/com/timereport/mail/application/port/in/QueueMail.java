package com.timereport.mail.application.port.in;

import com.timereport.mail.domain.MailMessage;
import com.timereport.mail.domain.MailReference;
import java.time.Instant;
import java.util.UUID;

public interface QueueMail {
    void enqueue(MailReference reference, MailMessage message, Instant expiresAt);
    void cancel(UUID accountId, String type);
}
