package com.timereport.invoicing.application.port.in;

import com.timereport.invoicing.domain.*;

import java.util.List;
import java.util.UUID;

public interface IssuedInvoices {
    List<InvoiceSummary> list();

    IssuedInvoiceDocument get(int invoiceNumber);

    InvoiceDeliveryDefaults deliveryDefaults(int invoiceNumber);

    InvoiceDelivery send(int invoiceNumber, String recipient, List<String> cc, List<String> bcc, String message, UUID requestId);

    default InvoiceDelivery send(int invoiceNumber, String recipient, List<String> cc, List<String> bcc, UUID requestId) {
        return send(invoiceNumber, recipient, cc, bcc, null, requestId);
    }

    default InvoiceDelivery send(int invoiceNumber, String recipient, UUID requestId) {
        return send(invoiceNumber, recipient, null, null, requestId);
    }

    InvoiceStatus setStatus(int invoiceNumber, boolean sent, boolean paid, long expectedVersion);
}
