package com.timereport.invoicing.adapter.out.mail;

import com.timereport.invoicing.application.port.out.InvoiceMail;
import com.timereport.invoicing.domain.InvoiceDelivery;
import com.timereport.invoicing.domain.IssuedInvoiceDocument;
import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.mail.adapter.out.SmtpAddress;
import com.timereport.mail.application.port.in.QueueMail;
import com.timereport.mail.domain.MailAttachment;
import com.timereport.mail.domain.MailMessage;
import com.timereport.mail.domain.MailReference;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.time.Duration;

@ApplicationScoped
public class QueuedInvoiceMail implements InvoiceMail {
    private final QueueMail queue;
    private final AccountIdentity identity;
    private final String from;

    public QueuedInvoiceMail(QueueMail queue, AccountIdentity identity, @ConfigProperty(name = "invoicing.mail-from") String from) {
        this.queue = queue;
        this.identity = identity;
        this.from = from;
    }

    @Override
    public void enqueue(IssuedInvoiceDocument document, InvoiceDelivery delivery, String replyTo) {
        var invoice = document.invoice();
        var seller = invoice.seller();
        String name = seller.sellerName().replaceAll("[\\p{Cntrl}]", " ");
        String text = "Hej!\n\nBifogat finns faktura " + invoice.invoiceNumber() + " från " + name
                + " för " + invoice.periodMonth() + ".\n\n"
                + (delivery.message().isBlank() ? "" : delivery.message() + "\n\n") + "Vid frågor kan du skicka mejl till " + replyTo + ".\n\nMed vänlig hälsning\n"
                + name + "\nSkickat via Tidrapporten";
        queue.enqueue(new MailReference(identity.accountId(), "invoice", delivery.id()),
                MailMessage.builder().recipient(delivery.recipient()).subject("Faktura " + invoice.invoiceNumber() + " från " + name).text(text)
                        .from(SmtpAddress.named(name + " via Tidrapporten", from)).replyTo(replyTo)
                        .cc(delivery.cc()).bcc(delivery.bcc())
                        .attachments(List.of(MailAttachment.of("Faktura-" + invoice.invoiceNumber() + ".pdf", "application/pdf", document.pdf())))
                        .build(), delivery.requestedAt().plus(Duration.ofDays(7)));
    }
}
