package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.IssuedInvoiceDocument;
import com.timereport.invoicing.domain.InvoiceDelivery;

public interface InvoiceMail {
    void enqueue(IssuedInvoiceDocument document, InvoiceDelivery delivery, String replyTo);
}
