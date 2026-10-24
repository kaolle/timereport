package com.timereport.invoicing.application.port.in;

import com.timereport.invoicing.domain.InvoiceNumbering;

public interface InvoiceNumberingSettings {
    InvoiceNumbering get();

    InvoiceNumbering set(int nextNumber);
}
