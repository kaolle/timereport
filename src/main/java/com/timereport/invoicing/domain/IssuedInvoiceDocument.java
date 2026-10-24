package com.timereport.invoicing.domain;

import lombok.Builder;

import java.util.Objects;

@Builder
public record IssuedInvoiceDocument(IssuedInvoice invoice, byte[] pdf) {
    public IssuedInvoiceDocument {
        Objects.requireNonNull(invoice);
        pdf = Objects.requireNonNull(pdf).clone();
    }

    @Override
    public byte[] pdf() {
        return pdf.clone();
    }
}
