package com.timereport.timeentry.adapter.in.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record SaveCustomerCodeRequest(@NotNull Boolean active,
        @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedVersion) {
}
