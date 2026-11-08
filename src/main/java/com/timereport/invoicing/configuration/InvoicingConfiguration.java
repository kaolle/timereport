package com.timereport.invoicing.configuration;

import com.timereport.invoicing.application.port.in.*;
import com.timereport.invoicing.application.port.out.*;
import com.timereport.invoicing.application.service.*;
import com.timereport.timeentry.application.port.in.TimeEntries;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

public class InvoicingConfiguration {
    @Produces
    @Singleton
    public IssuedInvoices issuedInvoices(InvoiceRepository invoices, InvoiceDeliveryRepository deliveries,
                                         InvoiceStatusRepository statuses, InvoiceMail mail, InvoiceTransaction transaction,
                                         InvoicingSettingsRepository settings, InvoiceCreationAccess access) {
        return new IssuedInvoiceService(invoices, deliveries, statuses, mail, transaction, settings, Clock.systemUTC(), access);
    }

    @Produces
    @Singleton
    public InvoicingSettings invoicingSettings(InvoicingSettingsRepository repository) {
        return new InvoicingSettingsService(repository);
    }

    @Produces
    @Singleton
    public InvoiceDrafts invoiceDrafts(InvoicingSettings settings, TimeEntries entries, InvoiceCreationAccess access) {
        return new InvoiceDraftService(settings, entries, access);
    }

    @Produces
    @Singleton
    public InvoiceNumberingSettings invoiceNumberingSettings(InvoiceRepository repository) {
        return new InvoiceNumberingService(repository);
    }

    @Produces
    @Singleton
    public InvoiceIssuance invoiceIssuance(InvoiceDrafts drafts, InvoicingSettings settings,
                                           InvoiceRepository repository, InvoiceTransaction transaction,
                                           InvoicePdfGenerator pdfGenerator, InvoiceCreationAccess access) {
        return new InvoiceIssuanceService(drafts, settings, repository, transaction, pdfGenerator, access);
    }
}
