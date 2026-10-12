package com.timereport.timeentry.domain;

import lombok.Builder;

import java.time.LocalDate;
import java.util.Objects;

@Builder
public record VacationDay(LocalDate date) {
    public VacationDay {
        Objects.requireNonNull(date, "Vacation date is required");
    }
}
