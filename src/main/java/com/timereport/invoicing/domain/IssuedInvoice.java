package com.timereport.invoicing.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Builder
public record IssuedInvoice(UUID id, int invoiceNumber, String periodMonth, LocalDate invoiceDate, LocalDate dueDate,
                            Instant issuedAt, SellerBillingProfile seller, BillingCustomer customer,
                            List<Line> lines, List<VatTotal> vatTotals, BigDecimal netAmount,
                            BigDecimal vatAmount, BigDecimal totalAmount) {
    public IssuedInvoice {
        Objects.requireNonNull(id);
        Objects.requireNonNull(periodMonth);
        Objects.requireNonNull(invoiceDate);
        Objects.requireNonNull(dueDate);
        Objects.requireNonNull(issuedAt);
        Objects.requireNonNull(seller);
        Objects.requireNonNull(customer);
        lines = List.copyOf(lines);
        vatTotals = List.copyOf(vatTotals);
        Objects.requireNonNull(netAmount);
        Objects.requireNonNull(vatAmount);
        Objects.requireNonNull(totalAmount);
        if (invoiceNumber < 1) throw new IllegalArgumentException("Fakturanumret måste vara positivt.");
        if (lines.isEmpty()) throw new IllegalArgumentException("Fakturan måste innehålla minst en rad.");
    }

    @Builder
    public record Line(String customerCode, String description, long workedMinutes, BigDecimal hourlyRate,
                       BigDecimal vatPercent, BigDecimal netAmount, BigDecimal vatAmount, BigDecimal totalAmount) {
        public Line {
            Objects.requireNonNull(customerCode);
            Objects.requireNonNull(description);
            Objects.requireNonNull(hourlyRate);
            Objects.requireNonNull(vatPercent);
            Objects.requireNonNull(netAmount);
            Objects.requireNonNull(vatAmount);
            Objects.requireNonNull(totalAmount);
        }
    }

    @Builder
    public record VatTotal(BigDecimal vatPercent, BigDecimal netAmount, BigDecimal vatAmount) {
        public VatTotal {
            Objects.requireNonNull(vatPercent);
            Objects.requireNonNull(netAmount);
            Objects.requireNonNull(vatAmount);
        }
    }
}
