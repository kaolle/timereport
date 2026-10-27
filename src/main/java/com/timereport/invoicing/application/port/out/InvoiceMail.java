package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.IssuedInvoiceDocument;

public interface InvoiceMail {
    void send(IssuedInvoiceDocument document, String recipient, String replyTo);
}
