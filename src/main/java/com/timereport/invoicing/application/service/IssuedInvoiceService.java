package com.timereport.invoicing.application.service;

import com.timereport.invoicing.application.port.in.IssuedInvoices;
import com.timereport.invoicing.application.port.out.*;
import com.timereport.invoicing.domain.*;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

public class IssuedInvoiceService implements IssuedInvoices {
    private final InvoiceRepository invoices;
    private final InvoiceDeliveryRepository deliveries;
    private final InvoiceStatusRepository statuses;
    private final InvoiceMail mail;
    private final InvoicingSettingsRepository settings;
    private final Clock clock;

    public IssuedInvoiceService(InvoiceRepository invoices, InvoiceDeliveryRepository deliveries,
                                InvoiceStatusRepository statuses, InvoiceMail mail, InvoicingSettingsRepository settings, Clock clock) {
        this.invoices = invoices;
        this.deliveries = deliveries;
        this.statuses = statuses;
        this.mail = mail;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public List<InvoiceSummary> list() {
        return List.copyOf(invoices.list());
    }

    @Override
    public IssuedInvoiceDocument get(int invoiceNumber) {
        return invoices.findDocument(invoiceNumber).orElseThrow(InvoiceNotFound::new);
    }

    @Override
    public InvoiceStatus setStatus(int number, boolean sent, boolean paid, long expectedVersion) {
        return statuses.setStatus(number, sent, paid, expectedVersion);
    }

    @Override
    public InvoiceDeliveryDefaults deliveryDefaults(int invoiceNumber) {
        return defaults(get(invoiceNumber));
    }

    private String companyEmail(IssuedInvoiceDocument document) {
        return settings.profile().map(profile -> profile.email()).filter(email -> !email.isBlank())
                .orElse(document.invoice().seller().email());
    }

    private InvoiceDeliveryDefaults defaults(IssuedInvoiceDocument document) {
        var customer = document.invoice().customer();
        String contact = settings.customers().stream().filter(current -> current.id().equals(customer.id()))
                .findFirst().map(current -> current.contactEmail()).orElse(customer.contactEmail());
        String company = companyEmail(document);
        return new InvoiceDeliveryDefaults(contact.isBlank() ? List.of() : List.of(contact),
                company.isBlank() ? List.of() : List.of(company));
    }

    @Override
    public InvoiceDelivery send(int invoiceNumber, String recipient, List<String> cc, List<String> bcc, UUID requestId) {
        var document = get(invoiceNumber);
        String address = InvoiceDelivery.email(recipient);
        String replyTo = companyEmail(document);
        if (replyTo.isBlank())
            throw new IllegalArgumentException("Ange företagets e-postadress under Ditt företag innan du skickar fakturan.");
        InvoiceDelivery.email(replyTo);
        var defaults = cc == null || bcc == null ? defaults(document) : null;
        var attempt = InvoiceDelivery.builder().id(requestId).invoiceNumber(invoiceNumber).recipient(address)
                .cc(cc == null ? defaults.cc() : cc).bcc(bcc == null ? defaults.bcc() : bcc)
                .state(InvoiceDelivery.State.SENDING).requestedAt(clock.instant()).build();
        if (!deliveries.start(attempt)) return deliveries.find(requestId).orElseThrow();
        InvoiceDelivery completed;
        try {
            mail.send(document, address, replyTo, attempt.cc(), attempt.bcc());
            completed = attempt.complete(InvoiceDelivery.State.SENT, clock.instant());
        } catch (RuntimeException failure) {
            // SMTP errors can be ambiguous: only an explicit user action may retry.
            completed = attempt.complete(InvoiceDelivery.State.FAILED, clock.instant());
        }
        deliveries.complete(completed);
        return completed;
    }
}
