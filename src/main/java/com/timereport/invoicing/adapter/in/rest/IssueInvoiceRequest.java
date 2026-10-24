package com.timereport.invoicing.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record IssueInvoiceRequest(@NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String month,
                                  @NotNull UUID customerId, List<@Valid InvoiceLineAdjustmentRequest> adjustments) {
    public IssueInvoiceRequest {
        adjustments = List.copyOf(adjustments == null ? List.of() : adjustments);
    }
}
