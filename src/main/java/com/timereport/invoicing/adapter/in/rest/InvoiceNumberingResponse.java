package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.InvoiceNumbering;
import lombok.Builder;

@Builder
public record InvoiceNumberingResponse(int nextNumber) {
    static InvoiceNumberingResponse from(InvoiceNumbering numbering) {
        return InvoiceNumberingResponse.builder().nextNumber(numbering.nextNumber()).build();
    }
}
