package com.timereport.mail.adapter.in.scheduler;

import com.timereport.mail.application.port.in.DispatchMail;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class LocalMailDispatcher {
    private final DispatchMail dispatcher;

    public LocalMailDispatcher(DispatchMail dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(every = "${mail.dispatch-interval:off}", concurrentExecution = SKIP)
    void drain() {
        dispatcher.dispatch();
    }
}
