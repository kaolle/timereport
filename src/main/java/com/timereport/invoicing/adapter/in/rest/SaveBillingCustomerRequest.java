package com.timereport.invoicing.adapter.in.rest;

import jakarta.validation.constraints.*;
import lombok.Builder;

@Builder
public record SaveBillingCustomerRequest(@NotBlank @Size(max = 40) String customerNumber,
        @NotBlank @Size(max = 160) String name, @Size(max = 40) String shortName,
        @NotBlank @Size(max = 200) String addressLine,
        @NotBlank @Size(max = 40) String postalCode, @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 80) String country, @Size(max = 40) String organizationNumber,
        @Size(max = 40) String vatNumber, @Email @Size(max = 254) String invoiceEmail,
        @Size(max = 160) String customerReference, @Size(max = 160) String ourReference,
                                         @NotNull @Min(0) @Max(365) Integer paymentTermsDays,
                                         @Email @Size(max = 254) String contactEmail) {
    @Override public String toString() { return "SaveBillingCustomerRequest[redacted]"; }
}
