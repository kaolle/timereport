package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.IssuedInvoice;

public interface InvoicePdfGenerator {
    byte[] generate(IssuedInvoice invoice);

    default byte[] generateDraft(IssuedInvoice invoice) {
        return generate(invoice);
    }
}
