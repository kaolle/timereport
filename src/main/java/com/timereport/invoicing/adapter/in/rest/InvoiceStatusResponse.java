package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.InvoiceStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record InvoiceStatusResponse(boolean sent, boolean paid, Instant sentAt, Instant paidAt, long version) {
    static InvoiceStatusResponse from(InvoiceStatus s) {
        return InvoiceStatusResponse.builder().sent(s.sent()).paid(s.paid()).sentAt(s.sentAt()).paidAt(s.paidAt()).version(s.version()).build();
    }
}
