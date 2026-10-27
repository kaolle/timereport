package com.timereport.invoicing.domain;

import lombok.Builder;

import java.time.Instant;

@Builder
public record InvoiceStatus(Instant sentAt, Instant paidAt, long version) {
    public InvoiceStatus {
        if (version < 0) throw new IllegalArgumentException("Ogiltig statusversion.");
    }

    public boolean sent() {
        return sentAt != null;
    }

    public boolean paid() {
        return paidAt != null;
    }
}
