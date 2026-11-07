package com.timereport.mail.application.service;

import com.timereport.mail.application.port.in.CleanMailOutbox;
import com.timereport.mail.application.port.out.MailOutbox;

import java.time.Clock;
import java.time.Duration;
import java.util.logging.Logger;

public class MailCleanupService implements CleanMailOutbox {
    private static final Logger LOG = Logger.getLogger(MailCleanupService.class.getName());
    private static final Duration RETENTION = Duration.ofDays(60);

    private final MailOutbox outbox;
    private final Clock clock;

    public MailCleanupService(MailOutbox outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public int clean() {
        var cutoff = clock.instant().minus(RETENTION);
        int deleted = outbox.deleteCompletedBefore(cutoff);
        LOG.info(() -> "Mail outbox cleanup completed: deleted=" + deleted + ", cutoff=" + cutoff);
        return deleted;
    }
}
