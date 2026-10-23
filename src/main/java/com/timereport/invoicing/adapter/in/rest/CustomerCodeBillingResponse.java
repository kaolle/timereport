package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.CustomerCodeBilling;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

@Builder
public record CustomerCodeBillingResponse(String code, boolean active, UUID customerId, String serviceDescription,
        BigDecimal hourlyRate, BigDecimal vatPercent) {
    static CustomerCodeBillingResponse from(CustomerCodeBilling c) {
        return CustomerCodeBillingResponse.builder().code(c.code()).active(c.active()).customerId(c.customerId())
                .serviceDescription(c.serviceDescription()).hourlyRate(c.hourlyRate()).vatPercent(c.vatPercent()).build();
    }
}
