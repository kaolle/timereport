package com.timereport.invoicing.adapter.out.mail;

import com.timereport.invoicing.application.port.out.InvoiceMail;
import com.timereport.invoicing.domain.IssuedInvoiceDocument;
import com.timereport.mail.adapter.out.SmtpAddress;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

@ApplicationScoped
public class SmtpInvoiceMail implements InvoiceMail {
    private final Mailer mailer;
    private final String from;

    public SmtpInvoiceMail(Mailer mailer, @ConfigProperty(name = "invoicing.mail-from") String from) {
        this.mailer = mailer;
        this.from = from;
    }

    @Override
    public void send(IssuedInvoiceDocument document, String recipient, String replyTo, List<String> cc, List<String> bcc, String message) {
        var invoice = document.invoice();
        var seller = invoice.seller();
        String name = seller.sellerName().replaceAll("[\\p{Cntrl}]", " ");
        String text = "Hej!\n\nBifogat finns faktura " + invoice.invoiceNumber() + " från " + name
                + " för " + invoice.periodMonth() + ".\n\n"
                + (message.isBlank() ? "" : message + "\n\n") + "Vid frågor kan du skicka mejl till " + replyTo + ".\n\nMed vänlig hälsning\n"
                + name + "\nSkickat via Tidrapporten";
        mailer.send(Mail.withText(recipient, "Faktura " + invoice.invoiceNumber() + " från " + name, text)
                .setFrom(SmtpAddress.named(name + " via Tidrapporten", from))
                .setReplyTo(replyTo)
                .addCc(cc.toArray(String[]::new))
                .addBcc(bcc.toArray(String[]::new))
                .addAttachment("Faktura-" + invoice.invoiceNumber() + ".pdf", document.pdf(), "application/pdf"));
    }
}
