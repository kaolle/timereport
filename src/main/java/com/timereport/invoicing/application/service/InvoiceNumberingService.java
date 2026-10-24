package com.timereport.invoicing.application.service;

import com.timereport.invoicing.application.port.in.InvoiceNumberingSettings;
import com.timereport.invoicing.application.port.out.InvoiceRepository;
import com.timereport.invoicing.domain.InvoiceNumbering;

public class InvoiceNumberingService implements InvoiceNumberingSettings {
    private final InvoiceRepository repository;

    public InvoiceNumberingService(InvoiceRepository repository) {
        this.repository = repository;
    }

    @Override
    public InvoiceNumbering get() {
        return InvoiceNumbering.builder().nextNumber(repository.getNextNumber()).build();
    }

    @Override
    public InvoiceNumbering set(int nextNumber) {
        var requested = InvoiceNumbering.builder().nextNumber(nextNumber).build();
        return InvoiceNumbering.builder().nextNumber(repository.setNextNumber(requested.nextNumber())).build();
    }
}
