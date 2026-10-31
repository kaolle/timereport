package com.timereport.invoicing.application.service;

import com.timereport.invoicing.application.port.out.*;
import com.timereport.invoicing.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class IssuedInvoiceServiceTest {
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoiceDeliveryRepository deliveries = mock(InvoiceDeliveryRepository.class);
    private final InvoiceStatusRepository statuses = mock(InvoiceStatusRepository.class);
    private final InvoiceMail mail = mock(InvoiceMail.class);
    private final InvoicingSettingsRepository settings = mock(InvoicingSettingsRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
    private final IssuedInvoiceService service = new IssuedInvoiceService(invoices, deliveries, statuses, mail, settings, clock);

    private IssuedInvoiceDocument document() {
        var seller = new SellerBillingProfile("Example AB", "Gatan 1", "12345", "Ort", "Sverige", "", "", "", "company@example.test", "SEK", 30, BigDecimal.valueOf(25), true);
        var customer = new BillingCustomer(UUID.randomUUID(), "1", "Kund AB", "", "Gatan 2", "12345", "Ort", "Sverige", "", "", "customer@example.test", "", "", 30, "contact@example.test");
        var line = new IssuedInvoice.Line("CODE", "Arbete", 60, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        var invoice = new IssuedInvoice(UUID.randomUUID(), 1063, "2026-10", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 24), clock.instant(), seller, customer,
                List.of(line), List.of(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        return new IssuedInvoiceDocument(invoice, new byte[]{1, 2, 3});
    }

    @Test
    void smtpFailureIsRecordedAndRepeatingTheSameRequestNeverResends() {
        UUID id = UUID.randomUUID();
        var document = document();
        when(invoices.findDocument(1063)).thenReturn(Optional.of(document));
        when(deliveries.start(any())).thenReturn(true, false);
        doThrow(new IllegalStateException("SMTP unavailable")).when(mail).send(any(), anyString(), anyString(), anyList(), anyList(), anyString());
        var result = service.send(1063, "customer@example.test", id);
        assertEquals(InvoiceDelivery.State.FAILED, result.state());
        when(deliveries.find(id)).thenReturn(Optional.of(result));
        assertEquals(result, service.send(1063, "customer@example.test", id));
        verify(mail, times(1)).send(document, "customer@example.test", "company@example.test", List.of("contact@example.test"), List.of("company@example.test"), "");
        verify(deliveries).complete(result);
        verifyNoInteractions(statuses);
    }

    @Test
    void lostCompletionLeavesPendingAttemptAndDoesNotResendOnRetry() {
        UUID id = UUID.randomUUID();
        when(invoices.findDocument(1063)).thenReturn(Optional.of(document()));
        when(deliveries.start(any())).thenReturn(true, false);
        doThrow(new IllegalStateException("database unavailable")).when(deliveries).complete(any());
        assertThrows(IllegalStateException.class, () -> service.send(1063, "customer@example.test", id));
        var captor = ArgumentCaptor.forClass(InvoiceDelivery.class);
        verify(deliveries).start(captor.capture());
        var pending = captor.getValue();
        when(deliveries.find(id)).thenReturn(Optional.of(pending));
        assertEquals(InvoiceDelivery.State.SENDING, service.send(1063, "customer@example.test", id).state());
        verify(mail, times(1)).send(any(), anyString(), anyString(), anyList(), anyList(), anyString());
    }

    @Test
    void multipleRecipientsAndHeaderInjectionAreRejectedBeforeAnyMailIsClaimed() {
        when(invoices.findDocument(1063)).thenReturn(Optional.of(document()));
        for (String email : List.of("a@example.test,b@example.test", "a@example.test\r\nBcc:evil@example.test", "Name <a@example.test>"))
            assertThrows(IllegalArgumentException.class, () -> service.send(1063, email, UUID.randomUUID()));
        verifyNoInteractions(deliveries, mail);
    }
}
