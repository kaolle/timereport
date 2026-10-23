package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.InvoiceDraft;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Builder
public record InvoiceDraftResponse(String month, UUID customerId, String customerName, LocalDate from, LocalDate to,
                                   List<WeekResponse> weeks, List<UnallocatedResponse> unallocated,
                                   long workedMinutes, BigDecimal netAmount, BigDecimal vatAmount,
                                   BigDecimal totalAmount) {
    public InvoiceDraftResponse { weeks = List.copyOf(weeks); unallocated = List.copyOf(unallocated); }
    public static InvoiceDraftResponse from(InvoiceDraft draft) {
        return InvoiceDraftResponse.builder().month(draft.month()).customerId(draft.customerId())
                .customerName(draft.customerName()).from(draft.from()).to(draft.to())
                .weeks(draft.weeks().stream().map(WeekResponse::from).toList())
                .unallocated(draft.unallocated().stream().map(UnallocatedResponse::from).toList())
                .workedMinutes(draft.workedMinutes()).netAmount(draft.netAmount()).vatAmount(draft.vatAmount())
                .totalAmount(draft.totalAmount()).build();
    }
    @Builder public record WeekResponse(int weekNumber, int weekYear, LocalDate from, LocalDate to,
                                        List<LineResponse> lines, long workedMinutes, BigDecimal netAmount,
                                        BigDecimal vatAmount, BigDecimal totalAmount) {
        public WeekResponse { lines = List.copyOf(lines); }
        static WeekResponse from(InvoiceDraft.Week week) {
            return WeekResponse.builder().weekNumber(week.weekNumber()).weekYear(week.weekYear()).from(week.from()).to(week.to())
                    .lines(week.lines().stream().map(LineResponse::from).toList()).workedMinutes(week.workedMinutes())
                    .netAmount(week.netAmount()).vatAmount(week.vatAmount()).totalAmount(week.totalAmount()).build();
        }
    }
    @Builder public record LineResponse(String customerCode, String description, long workedMinutes,
                                        BigDecimal hourlyRate, BigDecimal vatPercent, BigDecimal netAmount,
                                        BigDecimal vatAmount, BigDecimal totalAmount) {
        static LineResponse from(InvoiceDraft.Line line) {
            return LineResponse.builder().customerCode(line.customerCode()).description(line.description())
                    .workedMinutes(line.workedMinutes()).hourlyRate(line.hourlyRate()).vatPercent(line.vatPercent())
                    .netAmount(line.netAmount()).vatAmount(line.vatAmount()).totalAmount(line.totalAmount()).build();
        }
    }
    @Builder public record UnallocatedResponse(String customerCode, String reason, long workedMinutes) {
        static UnallocatedResponse from(InvoiceDraft.UnallocatedTime item) {
            return UnallocatedResponse.builder().customerCode(item.customerCode()).reason(item.reason())
                    .workedMinutes(item.workedMinutes()).build();
        }
    }
}
