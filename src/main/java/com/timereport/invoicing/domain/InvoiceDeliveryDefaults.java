package com.timereport.invoicing.domain;

import lombok.Builder;
import java.util.List;

@Builder
public record InvoiceDeliveryDefaults(List<String> cc, List<String> bcc) {
    public InvoiceDeliveryDefaults {
        cc = InvoiceDelivery.addresses(cc);
        bcc = InvoiceDelivery.addresses(bcc);
    }
}
