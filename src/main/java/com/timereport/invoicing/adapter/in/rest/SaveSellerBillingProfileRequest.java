package com.timereport.invoicing.adapter.in.rest;

import jakarta.validation.constraints.*;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record SaveSellerBillingProfileRequest(@NotBlank @Size(max = 160) String sellerName,
        @NotBlank @Size(max = 200) String addressLine, @NotBlank @Size(max = 40) String postalCode,
        @NotBlank @Size(max = 100) String city, @NotBlank @Size(max = 80) String country,
        @Size(max = 40) String organizationNumber, @Size(max = 40) String vatNumber,
        @Size(max = 80) String bankgiro, @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
        @NotNull @Min(0) @Max(365) Integer paymentTermsDays,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal defaultVatPercent,
        @NotNull Boolean fTaxRegistered) {
    @Override public String toString() { return "SaveSellerBillingProfileRequest[redacted]"; }
}
