package com.timereport.mail.adapter.out;

import com.timereport.invoicing.application.port.out.InvoiceDeliveryRepository;
import com.timereport.mail.application.port.out.MailDeliveryEvents;
import com.timereport.mail.domain.MailReference;
import com.timereport.mail.domain.MailState;
import com.timereport.subscription.application.port.in.SubscriptionRenewalDelivery;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.time.Instant;

@ApplicationScoped
public class InvoiceMailDeliveryEvents implements MailDeliveryEvents {
    private static final Logger LOG = Logger.getLogger(InvoiceMailDeliveryEvents.class);
    private final InvoiceDeliveryRepository invoices;
    private final SubscriptionRenewalDelivery reminders;

    public InvoiceMailDeliveryEvents(InvoiceDeliveryRepository invoices, SubscriptionRenewalDelivery reminders) {
        this.invoices = invoices;
        this.reminders = reminders;
    }

    @Override
    public void completed(MailReference reference, MailState state, Instant completedAt) {
        if (reference.type().equals("invoice"))
            invoices.completeFromMail(reference.accountId(), reference.id(), state == MailState.SENT, completedAt);
        if (reference.type().equals("subscription-renewal"))
            reminders.completed(reference.accountId(), reference.id(), state.name(), completedAt);
        if (state != MailState.SENT)
            LOG.warnf("Mail attempt ended with %s: type=%s id=%s account=%s", state, reference.type(), reference.id(), reference.accountId());
    }
}
