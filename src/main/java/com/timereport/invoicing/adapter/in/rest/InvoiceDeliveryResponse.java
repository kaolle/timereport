package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.InvoiceDelivery;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record InvoiceDeliveryResponse(UUID requestId, int invoiceNumber, String recipient, String state,
                                      Instant requestedAt, Instant completedAt) {
    static InvoiceDeliveryResponse from(InvoiceDelivery d) {
        return InvoiceDeliveryResponse.builder().requestId(d.id()).invoiceNumber(d.invoiceNumber()).recipient(d.recipient())
                .state(d.state().name()).requestedAt(d.requestedAt()).completedAt(d.completedAt()).build();
    }
}
