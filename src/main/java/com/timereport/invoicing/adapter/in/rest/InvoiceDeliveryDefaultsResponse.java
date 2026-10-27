package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.InvoiceDeliveryDefaults;
import lombok.Builder;
import java.util.List;

@Builder
public record InvoiceDeliveryDefaultsResponse(List<String> cc, List<String> bcc) {
    public InvoiceDeliveryDefaultsResponse { cc = List.copyOf(cc); bcc = List.copyOf(bcc); }
    static InvoiceDeliveryDefaultsResponse from(InvoiceDeliveryDefaults defaults) {
        return InvoiceDeliveryDefaultsResponse.builder().cc(defaults.cc()).bcc(defaults.bcc()).build();
    }
}
