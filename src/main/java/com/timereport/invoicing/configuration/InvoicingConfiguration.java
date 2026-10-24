package com.timereport.invoicing.configuration;

import com.timereport.invoicing.application.port.in.InvoicingSettings;
import com.timereport.invoicing.application.port.out.InvoicingSettingsRepository;
import com.timereport.invoicing.application.service.InvoicingSettingsService;
import com.timereport.invoicing.application.port.in.InvoiceDrafts;
import com.timereport.invoicing.application.service.InvoiceDraftService;
import com.timereport.invoicing.application.port.in.InvoiceIssuance;
import com.timereport.invoicing.application.port.in.InvoiceNumberingSettings;
import com.timereport.invoicing.application.port.out.InvoicePdfGenerator;
import com.timereport.invoicing.application.port.out.InvoiceRepository;
import com.timereport.invoicing.application.port.out.InvoiceTransaction;
import com.timereport.invoicing.application.service.InvoiceIssuanceService;
import com.timereport.invoicing.application.service.InvoiceNumberingService;
import com.timereport.timeentry.application.port.in.TimeEntries;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class InvoicingConfiguration {
    @Produces
    @Singleton
    public InvoicingSettings invoicingSettings(InvoicingSettingsRepository repository) {
        return new InvoicingSettingsService(repository);
    }

    @Produces
    @Singleton
    public InvoiceDrafts invoiceDrafts(InvoicingSettings settings, TimeEntries entries) {
        return new InvoiceDraftService(settings, entries);
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
                                           InvoicePdfGenerator pdfGenerator) {
        return new InvoiceIssuanceService(drafts, settings, repository, transaction, pdfGenerator);
    }
}
