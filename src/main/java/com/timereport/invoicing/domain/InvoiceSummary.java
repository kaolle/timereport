package com.timereport.invoicing.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Builder
public record InvoiceSummary(int invoiceNumber, String month, String customerName, String invoiceEmail,
                             Instant issuedAt, LocalDate dueDate, BigDecimal totalAmount, BigDecimal vatAmount,
                             String currencyCode, InvoiceStatus status, InvoiceDelivery lastDelivery) {
    public InvoiceSummary {
        Objects.requireNonNull(month);
        Objects.requireNonNull(customerName);
        Objects.requireNonNull(issuedAt);
        Objects.requireNonNull(dueDate);
        Objects.requireNonNull(totalAmount);
        Objects.requireNonNull(vatAmount);
        Objects.requireNonNull(currencyCode);
        Objects.requireNonNull(status);
        invoiceEmail = invoiceEmail == null ? "" : invoiceEmail;
    }
}
