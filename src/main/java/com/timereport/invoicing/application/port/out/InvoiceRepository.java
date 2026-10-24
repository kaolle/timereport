package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.IssuedInvoice;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository {
    int getNextNumber();

    int setNextNumber(int nextNumber);

    int allocateNextNumber();

    void save(IssuedInvoice invoice, byte[] pdf);

    Optional<byte[]> findPdf(int invoiceNumber);
}
