package com.timereport.subscription.adapter.out.mail;

import com.timereport.mail.adapter.out.SmtpAddress;
import com.timereport.mail.application.port.in.QueueMail;
import com.timereport.mail.domain.MailMessage;
import com.timereport.mail.domain.MailReference;
import com.timereport.subscription.application.port.out.SubscriptionReminderMail;
import com.timereport.subscription.domain.RenewalReminder;
import com.timereport.subscription.domain.SubscriptionPlan;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@ApplicationScoped
public class QueuedSubscriptionReminderMail implements SubscriptionReminderMail {
    public static final String TYPE = "subscription-renewal";
    private final QueueMail queue;
    private final String from;
    private final String manageUrl;

    public QueuedSubscriptionReminderMail(QueueMail queue, @ConfigProperty(name = "quarkus.mailer.from") String from,
                                          @ConfigProperty(name = "auth.public-url") String publicUrl) {
        this.queue = queue;
        this.from = SmtpAddress.named("Tidrapporten", from);
        var origin = URI.create(publicUrl);
        boolean local = "localhost".equals(origin.getHost()) || "127.0.0.1".equals(origin.getHost())
                || "::1".equals(origin.getHost()) || "[::1]".equals(origin.getHost());
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                || origin.getFragment() != null || origin.getPath() != null && !origin.getPath().isEmpty()
                && !origin.getPath().equals("/") || !(origin.getScheme().equals("https")
                || origin.getScheme().equals("http") && local))
            throw new IllegalArgumentException("PUBLIC_URL must be an HTTPS origin or localhost");
        this.manageUrl = publicUrl.replaceAll("/$", "") + "/?subscription=manage";
    }

    @Override
    public void queue(RenewalReminder reminder, String recipientEmail) {
        String plan = reminder.plan() == SubscriptionPlan.MONTHLY ? "månadsabonnemang" : "årsabonnemang";
        var locale = Locale.forLanguageTag("sv-SE");
        var amount = NumberFormat.getNumberInstance(locale);
        amount.setMinimumFractionDigits(2);
        amount.setMaximumFractionDigits(2);
        String date = DateTimeFormatter.ofPattern("d MMMM uuuu", locale)
                .withZone(ZoneId.of("Europe/Stockholm")).format(reminder.renewalAt());
        String body = "Ditt " + plan + " på Tidrapporten förnyas snart.\n\n"
                + "Nästa debiteringsdatum: " + date + "\n"
                + "Belopp: " + amount.format(reminder.amountIncludingVat()) + " kr inklusive moms.\n\n"
                + "Abonnemanget förnyas automatiskt om du inte säger upp det. "
                + "Om du inte längre använder tjänsten kan du säga upp abonnemanget före nästa debitering.\n\n"
                + "Logga in och hantera abonnemang:\n" + manageUrl + "\n\n"
                + "Du behåller din redan betalda åtkomst till periodens slut om du säger upp abonnemanget.";
        queue.enqueue(reference(reminder), MailMessage.builder().from(from).recipient(recipientEmail)
                .subject("Ditt abonnemang på Tidrapporten förnyas snart").text(body).build(), reminder.renewalAt());
    }

    @Override
    public boolean cancel(RenewalReminder reminder) {
        return queue.cancel(reference(reminder));
    }

    private MailReference reference(RenewalReminder reminder) {
        return MailReference.builder().accountId(reminder.accountId()).type(TYPE).id(reminder.mailId()).build();
    }
}
