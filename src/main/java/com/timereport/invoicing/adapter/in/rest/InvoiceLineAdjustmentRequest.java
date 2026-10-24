package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.InvoiceLineAdjustment;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record InvoiceLineAdjustmentRequest(@NotBlank @Size(max = 40) String customerCode,
                                           @NotBlank @Size(max = 240) String description,
                                           @NotNull @PositiveOrZero @Max(10_000_000) Long workedMinutes,
                                           @NotNull @DecimalMin("0") @DecimalMax("1000000") BigDecimal hourlyRate,
                                           @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal vatPercent) {
    public InvoiceLineAdjustment toDomain() {
        return InvoiceLineAdjustment.builder().customerCode(customerCode).description(description)
                .workedMinutes(workedMinutes).hourlyRate(hourlyRate).vatPercent(vatPercent).build();
    }
}
