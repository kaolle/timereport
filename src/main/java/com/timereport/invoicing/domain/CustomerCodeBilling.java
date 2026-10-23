package com.timereport.invoicing.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Builder(toBuilder = true)
public record CustomerCodeBilling(String code, boolean active, UUID customerId, String serviceDescription,
                                 BigDecimal hourlyRate, BigDecimal vatPercent) {
    public CustomerCodeBilling {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Kundkod krävs.");
        code = code.strip();
        if (customerId != null) {
            if (serviceDescription == null || serviceDescription.isBlank()) throw new IllegalArgumentException("Tjänstebeskrivning krävs.");
            serviceDescription = serviceDescription.strip();
            Objects.requireNonNull(hourlyRate, "Timpris krävs.");
            Objects.requireNonNull(vatPercent, "Momssats krävs.");
            if (hourlyRate.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("Timpris får inte vara negativt.");
            if (vatPercent.compareTo(BigDecimal.ZERO) < 0 || vatPercent.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("Momssats ska vara mellan 0 och 100 procent.");
            }
            hourlyRate = hourlyRate.setScale(2, java.math.RoundingMode.HALF_UP);
            vatPercent = vatPercent.setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}
