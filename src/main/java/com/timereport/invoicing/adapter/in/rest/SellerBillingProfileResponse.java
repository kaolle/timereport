package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.domain.SellerBillingProfile;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record SellerBillingProfileResponse(String sellerName, String addressLine, String postalCode, String city,
        String country, String organizationNumber, String vatNumber, String bankgiro, String email,
        String currencyCode, int paymentTermsDays, BigDecimal defaultVatPercent, boolean fTaxRegistered) {
    static SellerBillingProfileResponse from(SellerBillingProfile p) {
        return SellerBillingProfileResponse.builder().sellerName(p.sellerName()).addressLine(p.addressLine())
                .postalCode(p.postalCode()).city(p.city()).country(p.country()).organizationNumber(p.organizationNumber())
                .vatNumber(p.vatNumber()).bankgiro(p.bankgiro()).email(p.email()).currencyCode(p.currencyCode())
                .paymentTermsDays(p.paymentTermsDays()).defaultVatPercent(p.defaultVatPercent()).fTaxRegistered(p.fTaxRegistered()).build();
    }
}
