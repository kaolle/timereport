package com.timereport.mail.configuration;

import com.timereport.mail.application.port.in.*;
import com.timereport.mail.application.port.out.*;
import com.timereport.mail.application.service.*;
import com.timereport.mail.domain.RetryPolicy;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;

public class MailConfiguration {
    @Produces
    @Singleton
    public QueueMail queueMail(MailOutbox outbox, MailQueuedSignal signal) {
        return new MailQueueService(outbox, signal, Clock.systemUTC());
    }

    @Produces
    @Singleton
    public DispatchMail dispatchMail(MailOutbox outbox, MailTransport transport, MailDeliveryEvents events,
                                     MailTransaction transaction) {
        return new MailDispatchService(outbox, transport, events, transaction,
                new RetryPolicy(4, Duration.ofMinutes(1), Duration.ofHours(2)), Clock.systemUTC(), 20);
    }

    @Produces
    @Singleton
    public CleanMailOutbox cleanMailOutbox(MailOutbox outbox) {
        return new MailCleanupService(outbox, Clock.systemUTC());
    }
}
