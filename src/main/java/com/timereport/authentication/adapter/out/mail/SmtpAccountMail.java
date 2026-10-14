package com.timereport.authentication.adapter.out.mail;

import com.timereport.authentication.application.port.out.AccountMail;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SmtpAccountMail implements AccountMail {
    private final Mailer mailer;
    @ConfigProperty(name = "auth.public-url")
    String publicUrl;

    public SmtpAccountMail(Mailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public void send(String email, String purpose, String token) {
        String title = purpose.equals("reset") ? "Återställ ditt lösenord" : "Bekräfta ditt konto";
        // Fragment avoids placing the secret in HTTP access logs or Referer headers.
        String link = publicUrl + "/#" + purpose + "=" + token;
        mailer.send(Mail.withText(email, title, title + " i Tidrapport:\n\n" + link
                + "\n\nLänken gäller i 30 minuter och kan användas en gång. Ignorera mejlet om du inte begärde det."));
    }
}
