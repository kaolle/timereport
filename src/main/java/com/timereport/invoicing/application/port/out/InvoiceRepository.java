package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.InvoiceSummary;
import com.timereport.invoicing.domain.IssuedInvoice;
import com.timereport.invoicing.domain.IssuedInvoiceDocument;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository {
    int getNextNumber();

    int setNextNumber(int nextNumber);

    int allocateNextNumber();

    void save(IssuedInvoice invoice, byte[] pdf);

    Optional<byte[]> findPdf(int invoiceNumber);

    Optional<IssuedInvoiceDocument> findDocument(int invoiceNumber);

    List<InvoiceSummary> list();
}
