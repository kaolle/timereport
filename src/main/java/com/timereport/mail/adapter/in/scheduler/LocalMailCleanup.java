package com.timereport.mail.adapter.in.scheduler;

import com.timereport.mail.application.port.in.CleanMailOutbox;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class LocalMailCleanup {
    private final CleanMailOutbox cleaner;

    public LocalMailCleanup(CleanMailOutbox cleaner) {
        this.cleaner = cleaner;
    }

    @Scheduled(cron = "${mail.cleanup-cron:off}", concurrentExecution = SKIP)
    void clean() {
        cleaner.clean();
    }
}
