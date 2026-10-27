package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.InvoiceSummary;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Builder
public record InvoiceSummaryResponse(int invoiceNumber, String month, String customerName, String invoiceEmail,
                                     Instant issuedAt, LocalDate dueDate, BigDecimal totalAmount, BigDecimal vatAmount,
                                     String currencyCode, InvoiceStatusResponse status,
                                     InvoiceDeliveryResponse lastDelivery) {
    static InvoiceSummaryResponse from(InvoiceSummary s) {
        return InvoiceSummaryResponse.builder().invoiceNumber(s.invoiceNumber()).month(s.month()).customerName(s.customerName())
                .invoiceEmail(s.invoiceEmail()).issuedAt(s.issuedAt()).dueDate(s.dueDate()).totalAmount(s.totalAmount())
                .vatAmount(s.vatAmount()).currencyCode(s.currencyCode()).status(InvoiceStatusResponse.from(s.status()))
                .lastDelivery(s.lastDelivery() == null ? null : InvoiceDeliveryResponse.from(s.lastDelivery())).build();
    }
}
