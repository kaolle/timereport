package com.timereport.invoicing.adapter.in.rest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record SetInvoiceStatusRequest(@NotNull Boolean sent, @NotNull Boolean paid,
                                      @NotNull @Min(0) Long expectedVersion) {
}
