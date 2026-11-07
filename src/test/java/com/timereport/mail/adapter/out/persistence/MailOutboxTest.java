package com.timereport.mail.adapter.out.persistence;

import com.timereport.mail.application.port.in.DispatchMail;
import com.timereport.mail.application.port.in.QueueMail;
import com.timereport.mail.application.port.out.MailTransport;
import com.timereport.mail.application.port.out.MailTransaction;
import com.timereport.mail.domain.MailMessage;
import com.timereport.mail.domain.MailReference;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class MailOutboxTest {
    @Inject DataSource dataSource;
    @Inject QueueMail queue;
    @Inject DispatchMail dispatcher;
    @Inject MailTransaction transaction;
    @Inject JdbcMailOutbox outbox;
    @InjectMock MailTransport transport;

    @Test
    void failedSendIsEncryptedRetriedAndEventuallyClearsItsPayload() throws Exception {
        var reference = reference();
        var message = message("Secret reset link #reset=top-secret-token");
        transaction.execute(() -> queue.enqueue(reference, message, Instant.now().plus(Duration.ofHours(1))));
        byte[] stored = payload(reference);
        assertNotNull(stored);
        assertFalse(new String(stored, StandardCharsets.UTF_8).contains("top-secret-token"));
        assertFalse(new String(stored, StandardCharsets.UTF_8).contains("recipient@example.test"));

        doThrow(new IllegalStateException("SMTP rejected token top-secret-token")).doNothing()
                .when(transport).send(reference, message);
        assertEquals(1, dispatcher.dispatch());
        assertEquals("PENDING", state(reference));
        assertNotNull(payload(reference));
        makeDue(reference);
        assertEquals(1, dispatcher.dispatch());
        verify(transport, times(2)).send(reference, message);
        assertEquals("SENT", state(reference));
        assertNull(payload(reference));
        assertEquals(0, dispatcher.dispatch());
    }

    @Test
    void abandonedLeaseCanBeReclaimedAndStaleWorkerCannotCompleteIt() throws Exception {
        var reference = reference();
        transaction.execute(() -> queue.enqueue(reference, message("invoice body"), Instant.now().plus(Duration.ofHours(1))));
        var stale = outbox.claim(Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
        try (var connection = dataSource.getConnection(); var update = connection.prepareStatement(
                "UPDATE mail_outbox SET lease_until=now()-interval '1 second' WHERE id=?")) {
            update.setObject(1, reference.id());
            update.executeUpdate();
        }
        assertEquals(1, dispatcher.dispatch());
        var result = new java.util.concurrent.atomic.AtomicBoolean();
        transaction.execute(() -> result.set(outbox.finish(stale, com.timereport.mail.domain.MailState.SENT,
                Instant.now(), Instant.now(), null)));
        assertFalse(result.get());
        assertEquals("SENT", state(reference));
    }

    private MailReference reference() throws Exception {
        UUID account = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO accounts(id,email,password_hash,verified) VALUES(?,?,?,true)")) {
            statement.setObject(1, account);
            statement.setString(2, account + "@example.test");
            statement.setString(3, "test");
            statement.executeUpdate();
        }
        return new MailReference(account, "test", UUID.randomUUID());
    }

    private MailMessage message(String text) {
        return MailMessage.builder().from("sender@example.test").recipient("recipient@example.test")
                .subject("Test message").text(text).cc(List.of()).bcc(List.of()).attachments(List.of()).build();
    }

    private byte[] payload(MailReference reference) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT payload FROM mail_outbox WHERE id=?")) {
            statement.setObject(1, reference.id());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBytes(1);
            }
        }
    }

    private String state(MailReference reference) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT state FROM mail_outbox WHERE id=?")) {
            statement.setObject(1, reference.id());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private void makeDue(MailReference reference) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE mail_outbox SET next_attempt_at=now()-interval '1 second' WHERE id=?")) {
            statement.setObject(1, reference.id());
            statement.executeUpdate();
        }
    }
}
