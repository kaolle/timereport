package com.timereport.timeentry.adapter.in.rest;

import com.timereport.timeentry.domain.VacationDay;
import lombok.Builder;

import java.time.LocalDate;

@Builder
public record VacationDayResponse(LocalDate date) {
    static VacationDayResponse from(VacationDay day) {
        return VacationDayResponse.builder().date(day.date()).build();
    }
}
