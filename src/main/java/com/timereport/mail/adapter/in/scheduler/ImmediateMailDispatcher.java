package com.timereport.mail.adapter.in.scheduler;

import com.timereport.mail.application.port.in.DispatchMail;
import com.timereport.mail.domain.MailReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ImmediateMailDispatcher {
    private static final Logger LOG = Logger.getLogger(ImmediateMailDispatcher.class);
    private final DispatchMail dispatcher;

    public ImmediateMailDispatcher(DispatchMail dispatcher) {
        this.dispatcher = dispatcher;
    }

    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) MailReference reference) {
        try {
            // Run before the HTTP request ends; request-based Cloud Run CPU may stop afterwards.
            int processed = dispatcher.dispatch(reference);
            LOG.infof("Immediate mail dispatch attempt completed: processed=%d", processed);
        } catch (RuntimeException failure) {
            // The committed outbox row remains for the scheduled recovery pass. Do not log
            // provider exception text because it may include mail contents or credentials.
            LOG.warnf("Immediate mail dispatch failed; scheduled recovery will retry: type=%s id=%s",
                    reference.type(), reference.id());
        }
    }
}
