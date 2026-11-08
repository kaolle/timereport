package com.timereport.invoicing.application.port.out;

/** Access to creating invoice drafts, PDF previews, issued invoices and deliveries. */
public interface InvoiceCreationAccess {
    void requireAccess();
}
