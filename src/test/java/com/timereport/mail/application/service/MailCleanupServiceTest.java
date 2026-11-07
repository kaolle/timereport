package com.timereport.mail.application.service;

import com.timereport.mail.application.port.out.MailOutbox;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailCleanupServiceTest {
    @Test
    void removesRowsCompletedAtLeastSixtyDaysAgo() {
        var outbox = mock(MailOutbox.class);
        var now = Instant.parse("2026-11-06T12:00:00Z");
        var cutoff = Instant.parse("2026-09-07T12:00:00Z");
        when(outbox.deleteCompletedBefore(cutoff)).thenReturn(4);

        int deleted = new MailCleanupService(outbox, Clock.fixed(now, ZoneOffset.UTC)).clean();

        assertEquals(4, deleted);
        verify(outbox).deleteCompletedBefore(cutoff);
    }
}
