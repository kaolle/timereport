package com.timereport.mail.application.service;

import com.timereport.mail.application.port.in.DispatchMail;
import com.timereport.mail.application.port.out.*;
import com.timereport.mail.domain.*;
import java.time.Clock;
import java.time.Duration;

public class MailDispatchService implements DispatchMail {
    private final MailOutbox outbox;
    private final MailTransport transport;
    private final MailDeliveryEvents events;
    private final MailTransaction transaction;
    private final RetryPolicy retry;
    private final Clock clock;
    private final int batchSize;

    public MailDispatchService(MailOutbox outbox, MailTransport transport, MailDeliveryEvents events,
                               MailTransaction transaction, RetryPolicy retry, Clock clock, int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Invalid mail batch size");
        this.outbox = outbox;
        this.transport = transport;
        this.events = events;
        this.transaction = transaction;
        this.retry = retry;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public int dispatch() {
        int count = 0;
        // Bounded HTTP work also works with request-based Cloud Run CPU allocation.
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (count < batchSize && System.nanoTime() < deadline) {
            var now = clock.instant();
            var next = outbox.claim(now, now.plus(Duration.ofMinutes(5)));
            if (next.isEmpty()) break;
            deliver(next.get());
            count++;
        }
        return count;
    }

    @Override
    public int dispatch(MailReference reference) {
        var now = clock.instant();
        var next = outbox.claim(reference, now, now.plus(Duration.ofMinutes(5)));
        if (next.isEmpty()) return 0;
        deliver(next.get());
        return 1;
    }

    private void deliver(MailClaim claim) {
        MailState result;
        String error = null;
        if (!claim.expiresAt().isAfter(clock.instant())) {
            result = MailState.EXPIRED;
            error = "EXPIRED";
        } else if (claim.attempt() > retry.maxAttempts()) {
            result = MailState.FAILED;
            error = "ATTEMPTS_EXHAUSTED";
        } else {
            try {
                transport.send(claim.reference(), claim.message());
                result = MailState.SENT;
            } catch (RuntimeException failure) {
                // Never persist/log provider exception text: it can contain addresses, bodies or credentials.
                result = claim.attempt() >= retry.maxAttempts() ? MailState.FAILED : MailState.PENDING;
                error = "TRANSPORT_ERROR";
            }
        }
        var completedAt = clock.instant();
        var nextAttempt = completedAt.plus(retry.delayAfter(claim.attempt()));
        MailState state = result;
        String code = error;
        // Completion and the owning capability's status commit together. DB errors must propagate,
        // not be mistaken for SMTP failures. An expired lease recovers a crashed worker.
        transaction.execute(() -> {
            if (outbox.finish(claim, state, completedAt, nextAttempt, code) && state.terminal())
                events.completed(claim.reference(), state, completedAt);
        });
    }
}
