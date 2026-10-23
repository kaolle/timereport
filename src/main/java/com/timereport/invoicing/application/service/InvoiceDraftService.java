package com.timereport.invoicing.application.service;

import com.timereport.invoicing.application.port.in.InvoiceDrafts;
import com.timereport.invoicing.application.port.in.InvoicingSettings;
import com.timereport.invoicing.domain.CustomerCodeBilling;
import com.timereport.invoicing.domain.InvoiceDraft;
import com.timereport.timeentry.application.port.in.TimeEntries;
import com.timereport.timeentry.domain.VersionedTimeEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InvoiceDraftService implements InvoiceDrafts {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final InvoicingSettings settings;
    private final TimeEntries entries;

    public InvoiceDraftService(InvoicingSettings settings, TimeEntries entries) {
        this.settings = settings; this.entries = entries;
    }

    @Override
    public InvoiceDraft create(String month, UUID customerId) {
        if (month == null || !month.matches("\\d{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException("Välj en giltig månad.");
        if (customerId == null) throw new IllegalArgumentException("Välj en kund.");
        LocalDate from = LocalDate.parse(month + "-01");
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        var customer = settings.customers().stream().filter(c -> c.id().equals(customerId)).findFirst()
                .orElseThrow(() -> new InvoicingConflict("Kunden finns inte."));
        Map<String, CustomerCodeBilling> codeSettings = new HashMap<>();
        settings.customerCodes().forEach(code -> codeSettings.put(code.code(), code));
        Map<LocalDate, List<VersionedTimeEntry>> byWeek = new HashMap<>();
        List<InvoiceDraft.UnallocatedTime> unallocated = new ArrayList<>();
        Map<String, Long> unallocatedTotals = new HashMap<>();
        Map<String, String> unallocatedReasons = new HashMap<>();
        for (var versioned : entries.list(from, to)) {
            if (versioned.deleted()) continue;
            var entry = versioned.entry();
            var code = entry.customerCode() == null ? null : entry.customerCode().value();
            var billing = code == null ? null : codeSettings.get(code);
            if (billing == null || billing.customerId() == null || !billing.customerId().equals(customerId)) {
                String reason = code == null ? "Tiden saknar kundkod" : billing == null || billing.customerId() == null
                        ? "Kundkoden saknar faktureringskoppling" : "Kundkoden är kopplad till en annan kund";
                String key = code == null ? "" : code;
                unallocatedTotals.merge(key, entry.workedMinutes(), Long::sum);
                unallocatedReasons.put(key, reason);
                continue;
            }
            byWeek.computeIfAbsent(entry.date().with(WeekFields.ISO.dayOfWeek(), 1), ignored -> new ArrayList<>()).add(versioned);
        }
        unallocatedTotals.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(row ->
                unallocated.add(InvoiceDraft.UnallocatedTime.builder().customerCode(row.getKey().isEmpty() ? null : row.getKey())
                        .reason(unallocatedReasons.get(row.getKey())).workedMinutes(row.getValue()).build()));
        List<InvoiceDraft.Week> weeks = new ArrayList<>();
        for (LocalDate weekStart = from.with(WeekFields.ISO.dayOfWeek(), 1);
             !weekStart.isAfter(to); weekStart = weekStart.plusWeeks(1)) {
            LocalDate rangeFrom = weekStart.isBefore(from) ? from : weekStart;
            LocalDate rangeTo = weekStart.plusDays(6).isAfter(to) ? to : weekStart.plusDays(6);
            Map<String, Long> minutesByCode = new HashMap<>();
            for (var row : byWeek.getOrDefault(weekStart, List.of())) {
                String code = row.entry().customerCode().value();
                minutesByCode.merge(code, row.entry().workedMinutes(), Long::sum);
            }
            List<InvoiceDraft.Line> lines = minutesByCode.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(row -> line(row.getKey(), row.getValue(), codeSettings.get(row.getKey()))).toList();
            long minutes = lines.stream().mapToLong(InvoiceDraft.Line::workedMinutes).sum();
            BigDecimal net = sum(lines, InvoiceDraft.Line::netAmount);
            BigDecimal vat = sum(lines, InvoiceDraft.Line::vatAmount);
            weeks.add(InvoiceDraft.Week.builder().weekNumber(weekStart.get(WeekFields.ISO.weekOfWeekBasedYear()))
                    .weekYear(weekStart.get(WeekFields.ISO.weekBasedYear())).from(rangeFrom).to(rangeTo).lines(lines)
                    .workedMinutes(minutes).netAmount(net).vatAmount(vat).totalAmount(net.add(vat)).build());
        }
        long minutes = weeks.stream().mapToLong(InvoiceDraft.Week::workedMinutes).sum();
        BigDecimal net = sum(weeks, InvoiceDraft.Week::netAmount);
        BigDecimal vat = sum(weeks, InvoiceDraft.Week::vatAmount);
        return InvoiceDraft.builder().month(month).customerId(customerId).customerName(customer.name()).from(from).to(to)
                .weeks(weeks).unallocated(unallocated).workedMinutes(minutes).netAmount(net).vatAmount(vat)
                .totalAmount(net.add(vat)).build();
    }

    private InvoiceDraft.Line line(String code, long minutes, CustomerCodeBilling pricing) {
        BigDecimal net = pricing.hourlyRate().multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal vat = net.multiply(pricing.vatPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return InvoiceDraft.Line.builder().customerCode(code).description(pricing.serviceDescription()).workedMinutes(minutes)
                .hourlyRate(pricing.hourlyRate()).vatPercent(pricing.vatPercent()).netAmount(net).vatAmount(vat)
                .totalAmount(net.add(vat)).build();
    }

    private static <T> BigDecimal sum(List<T> values, java.util.function.Function<T, BigDecimal> amount) {
        return values.stream().map(amount).reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
