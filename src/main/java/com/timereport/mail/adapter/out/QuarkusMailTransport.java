package com.timereport.mail.adapter.out;

import com.timereport.mail.application.port.out.MailTransport;
import com.timereport.mail.domain.MailMessage;
import com.timereport.mail.domain.MailReference;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class QuarkusMailTransport implements MailTransport {
    private final Mailer mailer;

    public QuarkusMailTransport(Mailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public void send(MailReference reference, MailMessage message) {
        var mail = Mail.withText(message.recipient(), message.subject(), message.text()).setFrom(message.from());
        if (message.replyTo() != null) mail.setReplyTo(message.replyTo());
        mail.addCc(message.cc().toArray(String[]::new));
        mail.addBcc(message.bcc().toArray(String[]::new));
        for (var attachment : message.attachments())
            mail.addAttachment(attachment.name(), attachment.bytes(), attachment.contentType());
        mailer.send(mail);
    }
}
