package com.timereport.mail.application.service;

import com.timereport.mail.application.port.in.QueueMail;
import com.timereport.mail.application.port.out.MailOutbox;
import com.timereport.mail.domain.MailMessage;
import com.timereport.mail.domain.MailReference;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public class MailQueueService implements QueueMail {
    private final MailOutbox outbox;
    private final Clock clock;

    public MailQueueService(MailOutbox outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    public void enqueue(MailReference reference, MailMessage message, Instant expiresAt) {
        var now = clock.instant();
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("Mail already expired");
        outbox.enqueue(reference, message, now, expiresAt);
    }

    public void cancel(UUID accountId, String type) {
        outbox.cancel(accountId, type, clock.instant());
    }
}
