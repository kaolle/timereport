package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.InvoiceStatus;

public interface InvoiceStatusRepository {
    InvoiceStatus setStatus(int invoiceNumber, boolean sent, boolean paid, long expectedVersion);
}
