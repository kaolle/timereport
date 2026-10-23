package com.timereport.invoicing.configuration;

import com.timereport.invoicing.application.port.in.InvoicingSettings;
import com.timereport.invoicing.application.port.out.InvoicingSettingsRepository;
import com.timereport.invoicing.application.service.InvoicingSettingsService;
import com.timereport.invoicing.application.port.in.InvoiceDrafts;
import com.timereport.invoicing.application.service.InvoiceDraftService;
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
}
