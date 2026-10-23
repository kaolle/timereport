package com.timereport.invoicing.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Calculated preview only; this is not a numbered or issued invoice. */
@Builder
public record InvoiceDraft(String month, UUID customerId, String customerName, LocalDate from, LocalDate to,
                           List<Week> weeks, List<UnallocatedTime> unallocated, long workedMinutes,
                           BigDecimal netAmount, BigDecimal vatAmount, BigDecimal totalAmount) {
    public InvoiceDraft {
        Objects.requireNonNull(month); Objects.requireNonNull(customerId); Objects.requireNonNull(customerName);
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        weeks = List.copyOf(weeks); unallocated = List.copyOf(unallocated);
        Objects.requireNonNull(netAmount); Objects.requireNonNull(vatAmount); Objects.requireNonNull(totalAmount);
    }

    @Builder
    public record Week(int weekNumber, int weekYear, LocalDate from, LocalDate to, List<Line> lines,
                       long workedMinutes, BigDecimal netAmount, BigDecimal vatAmount, BigDecimal totalAmount) {
        public Week {
            Objects.requireNonNull(from); Objects.requireNonNull(to); lines = List.copyOf(lines);
            Objects.requireNonNull(netAmount); Objects.requireNonNull(vatAmount); Objects.requireNonNull(totalAmount);
        }
    }

    @Builder
    public record Line(String customerCode, String description, long workedMinutes, BigDecimal hourlyRate,
                       BigDecimal vatPercent, BigDecimal netAmount, BigDecimal vatAmount, BigDecimal totalAmount) {
        public Line {
            Objects.requireNonNull(customerCode); Objects.requireNonNull(description); Objects.requireNonNull(hourlyRate);
            Objects.requireNonNull(vatPercent); Objects.requireNonNull(netAmount); Objects.requireNonNull(vatAmount);
            Objects.requireNonNull(totalAmount);
        }
    }

    @Builder
    public record UnallocatedTime(String customerCode, String reason, long workedMinutes) {
        public UnallocatedTime { Objects.requireNonNull(reason); }
    }
}
