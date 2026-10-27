package com.timereport.invoicing.application.port.in;

import com.timereport.invoicing.domain.InvoiceDelivery;
import com.timereport.invoicing.domain.InvoiceStatus;
import com.timereport.invoicing.domain.InvoiceSummary;
import com.timereport.invoicing.domain.IssuedInvoiceDocument;

import java.util.List;
import java.util.UUID;

public interface IssuedInvoices {
    List<InvoiceSummary> list();

    IssuedInvoiceDocument get(int invoiceNumber);

    InvoiceDelivery send(int invoiceNumber, String recipient, UUID requestId);

    InvoiceStatus setStatus(int invoiceNumber, boolean sent, boolean paid, long expectedVersion);
}
