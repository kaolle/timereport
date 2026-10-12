package com.timereport.timeentry.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import lombok.Builder;

/** A single person's entries for a date; customer codes may differ between entries. */
@Builder(toBuilder = true)
public record WorkDay(LocalDate date, List<TimeEntry> entries) {
    public WorkDay {
        Objects.requireNonNull(date, "Date is required");
        entries = List.copyOf(Objects.requireNonNull(entries, "Entries are required"));
        var ids = new HashSet<UUID>();
        for (TimeEntry entry : entries) {
            if (!date.equals(entry.date())) {
                throw new IllegalArgumentException("All entries must belong to the work day's date");
            }
            if (!ids.add(entry.id())) {
                throw new IllegalArgumentException("Time entry IDs must be unique within a day");
            }
        }
        List<TimeEntry> chronological = entries.stream()
                .sorted(Comparator.comparing(TimeEntry::start))
                .toList();
        for (int i = 1; i < chronological.size(); i++) {
            if (chronological.get(i).start().isBefore(chronological.get(i - 1).end())) {
                throw new IllegalArgumentException("Time entries must not overlap");
            }
        }
    }

    public long workedMinutes() {
        return entries.stream().mapToLong(TimeEntry::workedMinutes).sum();
    }

    /** A form suggestion only; additional entries must explicitly use zero unless another deduction is intended. */
    public int suggestedDeductedMinutes() {
        return entries.isEmpty() ? TimeEntry.DEFAULT_DEDUCTED_MINUTES : 0;
    }
}
