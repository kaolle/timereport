package com.timereport.mail.adapter.out;

import com.timereport.mail.application.port.out.MailQueuedSignal;
import com.timereport.mail.domain.MailReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

@ApplicationScoped
public class CdiMailQueuedSignal implements MailQueuedSignal {
    private final Event<MailReference> events;

    public CdiMailQueuedSignal(Event<MailReference> events) {
        this.events = events;
    }

    @Override
    public void queued(MailReference reference) {
        events.fire(reference);
    }
}
