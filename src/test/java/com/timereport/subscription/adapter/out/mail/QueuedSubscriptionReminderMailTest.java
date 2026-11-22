package com.timereport.subscription.adapter.out.mail;

import com.timereport.mail.application.port.in.QueueMail;
import com.timereport.mail.domain.MailMessage;
import com.timereport.mail.domain.MailReference;
import com.timereport.subscription.domain.RenewalReminder;
import com.timereport.subscription.domain.SubscriptionPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class QueuedSubscriptionReminderMailTest {
    private final QueueMail queue = mock(QueueMail.class);

    @Test
    void reminderUsesEncryptedQueueReferenceExpiryAndSafeAccountLink() {
        var adapter = new QueuedSubscriptionReminderMail(queue, "noreply@example.test", "https://timereport.example.test");
        var reminder = RenewalReminder.builder().id(UUID.randomUUID()).accountId(UUID.randomUUID())
                .subscriptionId("sub_mail_test").plan(SubscriptionPlan.MONTHLY)
                .renewalAt(Instant.parse("2026-11-28T12:00:00Z")).dueAt(Instant.parse("2026-11-21T08:00:00Z"))
                .amountIncludingVat(new BigDecimal("61.25")).state(RenewalReminder.State.PLANNED)
                .mailId(UUID.randomUUID()).build();
        adapter.queue(reminder, "customer@example.test");
        var message = ArgumentCaptor.forClass(MailMessage.class);
        var reference = ArgumentCaptor.forClass(MailReference.class);
        verify(queue).enqueue(reference.capture(), message.capture(), eq(reminder.renewalAt()));
        assertEquals(reminder.accountId(), reference.getValue().accountId());
        assertEquals(reminder.mailId(), reference.getValue().id());
        assertEquals("subscription-renewal", reference.getValue().type());
        assertEquals("\"Tidrapporten\" <noreply@example.test>", message.getValue().from());
        assertEquals("customer@example.test", message.getValue().recipient());
        assertTrue(message.getValue().text().contains("61,25 kr inklusive moms"));
        assertTrue(message.getValue().text().contains("28 november 2026"));
        assertTrue(message.getValue().text().contains("förnyas automatiskt"));
        assertTrue(message.getValue().text().contains("https://timereport.example.test/?subscription=manage"));
        assertTrue(message.getValue().attachments().isEmpty());
        adapter.cancel(reminder);
        verify(queue).cancel(reference.getValue());
    }

    @Test
    void refusesPublicUrlsWithCredentialsExternalPathOrInsecureExternalOrigin() {
        for (String url : new String[]{"https://user:password@example.test", "https://example.test/redirect", "http://example.test"})
            assertThrows(IllegalArgumentException.class,
                    () -> new QueuedSubscriptionReminderMail(queue, "noreply@example.test", url));
        assertDoesNotThrow(() -> new QueuedSubscriptionReminderMail(queue, "noreply@example.test", "http://localhost:8080/"));
    }
}
