package com.timereport.timeentry.adapter.in.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Builder;

@Builder
public record SaveTimeEntryRequest(
        @NotNull LocalDate date,
        @NotNull LocalTime start,
        @NotNull LocalTime end,
        @Min(0) Integer deductedMinutes,
        @NotBlank String customerCode,
        String comment,
        @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedVersion) {
}
