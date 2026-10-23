package com.timereport.invoicing.adapter.in.rest;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

@Builder
public record SaveCustomerCodeBillingRequest(@NotNull UUID customerId,
        @NotBlank @Size(max = 240) String serviceDescription,
        @NotNull @DecimalMin("0.00") BigDecimal hourlyRate,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal vatPercent) {
}
