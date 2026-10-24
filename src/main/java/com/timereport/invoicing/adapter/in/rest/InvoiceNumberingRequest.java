package com.timereport.invoicing.adapter.in.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record InvoiceNumberingRequest(@NotNull @Min(1) @Max(999999999) Integer nextNumber) {
}
