package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.IssuedInvoiceDocument;

import java.util.List;

public interface InvoiceMail {
    void send(IssuedInvoiceDocument document, String recipient, String replyTo, List<String> cc, List<String> bcc, String message);
}
