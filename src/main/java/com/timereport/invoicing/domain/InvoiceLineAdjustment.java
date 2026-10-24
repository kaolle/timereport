package com.timereport.invoicing.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Objects;

@Builder
public record InvoiceLineAdjustment(String customerCode, String description, long workedMinutes,
                                    BigDecimal hourlyRate, BigDecimal vatPercent) {
    public InvoiceLineAdjustment {
        Objects.requireNonNull(customerCode);
        Objects.requireNonNull(description);
        Objects.requireNonNull(hourlyRate);
        Objects.requireNonNull(vatPercent);
        if (customerCode.isBlank() || description.isBlank())
            throw new IllegalArgumentException("Kundkod och fakturarad måste anges.");
        if (workedMinutes < 0 || workedMinutes > 10_000_000)
            throw new IllegalArgumentException("Timmarna måste vara noll eller fler.");
        if (hourlyRate.signum() < 0 || hourlyRate.compareTo(new BigDecimal("1000000")) > 0)
            throw new IllegalArgumentException("Timpriset är ogiltigt.");
        if (vatPercent.signum() < 0 || vatPercent.compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("Momssatsen måste vara mellan 0 och 100 procent.");
    }
}
