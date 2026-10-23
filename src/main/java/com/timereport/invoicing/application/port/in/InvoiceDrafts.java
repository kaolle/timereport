package com.timereport.invoicing.application.port.in;

import com.timereport.invoicing.domain.InvoiceDraft;

import java.util.UUID;

public interface InvoiceDrafts {
    InvoiceDraft create(String month, UUID customerId);
}
