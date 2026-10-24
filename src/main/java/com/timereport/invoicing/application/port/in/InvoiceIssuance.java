package com.timereport.invoicing.application.port.in;

import com.timereport.invoicing.domain.IssuedInvoiceDocument;
import com.timereport.invoicing.domain.InvoiceLineAdjustment;

import java.util.List;
import java.util.UUID;

public interface InvoiceIssuance {
    IssuedInvoiceDocument preview(String month, UUID customerId, List<InvoiceLineAdjustment> adjustments);

    IssuedInvoiceDocument issue(String month, UUID customerId, List<InvoiceLineAdjustment> adjustments);
}
