package com.timereport.timeentry.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

import lombok.Builder;

/** One same-day work interval in local business time, with whole-minute precision. */
@Builder(toBuilder = true)
public record TimeEntry(
        UUID id,
        LocalDate date,
        LocalTime start,
        LocalTime end,
        Integer deductedMinutes,
        CustomerCode customerCode,
        String comment) {

    public static final int DEFAULT_DEDUCTED_MINUTES = 30;

    public TimeEntry {
        Objects.requireNonNull(id, "Time entry ID is required");
        Objects.requireNonNull(date, "Date is required");
        Objects.requireNonNull(start, "Start time is required");
        Objects.requireNonNull(end, "End time is required");
        Objects.requireNonNull(customerCode, "Customer code is required");
        if (start.getSecond() != 0 || start.getNano() != 0
                || end.getSecond() != 0 || end.getNano() != 0) {
            throw new IllegalArgumentException("Times must have whole-minute precision");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("End time must be after start time on the same day");
        }
        deductedMinutes = deductedMinutes == null ? DEFAULT_DEDUCTED_MINUTES : deductedMinutes;
        if (deductedMinutes < 0 || deductedMinutes > Duration.between(start, end).toMinutes()) {
            throw new IllegalArgumentException("Deduction must be between zero and the interval length");
        }
        comment = comment == null ? "" : comment;
    }

    public long workedMinutes() {
        return Duration.between(start, end).toMinutes() - deductedMinutes;
    }
}
