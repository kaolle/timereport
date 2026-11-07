package com.timereport.authentication.adapter.out.mail;

import com.timereport.authentication.application.port.out.AccountMail;
import com.timereport.mail.adapter.out.SmtpAddress;
import com.timereport.mail.application.port.in.QueueMail;
import com.timereport.mail.domain.MailMessage;
import com.timereport.mail.domain.MailReference;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class QueuedAccountMail implements AccountMail {
    private final QueueMail queue;
    @ConfigProperty(name = "auth.public-url")
    String publicUrl;
    @ConfigProperty(name = "quarkus.mailer.from")
    String from;

    public QueuedAccountMail(QueueMail queue) {
        this.queue = queue;
    }

    @Override
    public void send(UUID accountId, String email, String purpose, String token, Instant expiresAt) {
        String title = purpose.equals("reset") ? "Återställ ditt lösenord" : "Bekräfta ditt konto";
        // Fragment avoids placing the secret in HTTP access logs or Referer headers.
        String link = publicUrl + "/#" + purpose + "=" + token;
        queue.enqueue(new MailReference(accountId, "auth-" + purpose, UUID.randomUUID()),
                MailMessage.builder().recipient(email).subject(title)
                        .text(title + " i Tidrapporten:\n\n" + link
                                + "\n\nLänken gäller i 30 minuter och kan användas en gång. Ignorera mejlet om du inte begärde det.")
                        .from(SmtpAddress.named("Tidrapporten", from)).cc(List.of()).bcc(List.of()).attachments(List.of()).build(), expiresAt);
    }

    @Override
    public void cancel(UUID accountId, String purpose) {
        queue.cancel(accountId, "auth-" + purpose);
    }
}
