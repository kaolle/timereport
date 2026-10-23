package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.BillingCustomer;
import lombok.Builder;

import java.util.UUID;

@Builder
public record BillingCustomerResponse(UUID id, String customerNumber, String name, String shortName, String addressLine,
        String postalCode, String city, String country, String organizationNumber, String vatNumber,
        String invoiceEmail, String customerReference, String ourReference, int paymentTermsDays) {
    static BillingCustomerResponse from(BillingCustomer c) {
        return BillingCustomerResponse.builder().id(c.id()).customerNumber(c.customerNumber()).name(c.name()).shortName(c.shortName())
                .addressLine(c.addressLine()).postalCode(c.postalCode()).city(c.city()).country(c.country())
                .organizationNumber(c.organizationNumber()).vatNumber(c.vatNumber()).invoiceEmail(c.invoiceEmail())
                .customerReference(c.customerReference()).ourReference(c.ourReference()).paymentTermsDays(c.paymentTermsDays()).build();
    }
}
