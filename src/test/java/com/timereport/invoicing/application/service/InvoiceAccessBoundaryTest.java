package com.timereport.invoicing.application.service;

import com.timereport.invoicing.application.port.in.InvoiceDrafts;
import com.timereport.invoicing.application.port.in.InvoicingSettings;
import com.timereport.invoicing.application.port.out.*;
import com.timereport.timeentry.application.port.in.TimeEntries;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class InvoiceAccessBoundaryTest {
    private final InvoiceCreationAccess access = mock(InvoiceCreationAccess.class);

    @Test
    void directDraftRequestChecksAccessBeforeReadingTimeEntries() {
        var settings = mock(InvoicingSettings.class);
        var entries = mock(TimeEntries.class);
        doThrow(new SubscriptionRequired()).when(access).requireAccess();

        assertThrows(SubscriptionRequired.class,
                () -> new InvoiceDraftService(settings, entries, access).create("2026-11", UUID.randomUUID()));
        verifyNoInteractions(settings, entries);
    }

    @Test
    void directIssueChecksAccessBeforeAllocatingAnInvoiceNumber() {
        var drafts = mock(InvoiceDrafts.class);
        var settings = mock(InvoicingSettings.class);
        var invoices = mock(InvoiceRepository.class);
        var transaction = mock(InvoiceTransaction.class);
        var pdf = mock(InvoicePdfGenerator.class);
        doThrow(new SubscriptionRequired()).when(access).requireAccess();

        assertThrows(SubscriptionRequired.class,
                () -> new InvoiceIssuanceService(drafts, settings, invoices, transaction, pdf, access)
                        .issue("2026-11", UUID.randomUUID(), List.of()));
        verifyNoInteractions(drafts, settings, invoices, transaction, pdf);
    }

    @Test
    void directEmailRequestChecksAccessBeforeReadingTheStoredInvoice() {
        var invoices = mock(InvoiceRepository.class);
        doThrow(new SubscriptionRequired()).when(access).requireAccess();
        var service = new IssuedInvoiceService(invoices, mock(InvoiceDeliveryRepository.class),
                mock(InvoiceStatusRepository.class), mock(InvoiceMail.class), mock(InvoiceTransaction.class),
                mock(InvoicingSettingsRepository.class), Clock.systemUTC(), access);

        assertThrows(SubscriptionRequired.class,
                () -> service.send(1061, "customer@example.test", UUID.randomUUID()));
        verifyNoInteractions(invoices);
    }
}
