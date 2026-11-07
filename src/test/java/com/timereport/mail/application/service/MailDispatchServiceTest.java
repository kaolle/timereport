package com.timereport.mail.application.service;

import com.timereport.mail.application.port.out.*;
import com.timereport.mail.domain.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailDispatchServiceTest {
    private final Instant now = Instant.parse("2026-11-02T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final MailOutbox outbox = mock(MailOutbox.class);
    private final MailTransport transport = mock(MailTransport.class);
    private final MailDeliveryEvents events = mock(MailDeliveryEvents.class);
    private final MailTransaction transaction = Runnable::run;
    private final MailDispatchService service = new MailDispatchService(outbox, transport, events, transaction,
            new RetryPolicy(3, Duration.ofMinutes(1), Duration.ofHours(1)), clock, 20);

    @Test
    void exhaustedMailBecomesFinalFailureAndNotifiesItsOwner() {
        var claim = claim(3, now.plusSeconds(600));
        when(outbox.claim(any(), any())).thenReturn(Optional.of(claim), Optional.empty());
        when(outbox.finish(eq(claim), eq(MailState.FAILED), any(), any(), eq("TRANSPORT_ERROR"))).thenReturn(true);
        doThrow(new IllegalStateException("secret SMTP response")).when(transport).send(any(), any());

        assertEquals(1, service.dispatch());
        verify(events).completed(claim.reference(), MailState.FAILED, now);
    }

    @Test
    void expiredVerificationLinkIsNotSent() {
        var claim = claim(1, now.minusSeconds(1));
        when(outbox.claim(any(), any())).thenReturn(Optional.of(claim), Optional.empty());
        when(outbox.finish(eq(claim), eq(MailState.EXPIRED), any(), any(), eq("EXPIRED"))).thenReturn(true);

        assertEquals(1, service.dispatch());
        verifyNoInteractions(transport);
        verify(events).completed(claim.reference(), MailState.EXPIRED, now);
    }

    private MailClaim claim(int attempt, Instant expiresAt) {
        return new MailClaim(new MailReference(UUID.randomUUID(), "auth-reset", UUID.randomUUID()),
                new MailMessage("sender@example.test", "recipient@example.test", null, List.of(), List.of(),
                        "Reset", "secret link", List.of()), expiresAt, attempt, UUID.randomUUID());
    }
}
