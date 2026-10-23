package com.timereport.invoicing.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

@Builder(toBuilder = true)
public record SellerBillingProfile(String sellerName, String addressLine, String postalCode, String city,
                                   String country, String organizationNumber, String vatNumber, String bankgiro,
                                   String email, String currencyCode, int paymentTermsDays,
                                   BigDecimal defaultVatPercent, boolean fTaxRegistered) {
    public SellerBillingProfile {
        sellerName = required(sellerName, "Företagsnamn krävs.");
        addressLine = required(addressLine, "Adress krävs.");
        postalCode = required(postalCode, "Postnummer krävs.");
        city = required(city, "Ort krävs.");
        country = required(country, "Land krävs.");
        organizationNumber = clean(organizationNumber);
        vatNumber = clean(vatNumber);
        bankgiro = clean(bankgiro);
        email = clean(email);
        try {
            currencyCode = Currency.getInstance(required(currencyCode, "Valutakod krävs.").toUpperCase()).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ange en giltig valutakod, till exempel SEK.");
        }
        Objects.requireNonNull(defaultVatPercent, "Momssats krävs.");
        if (paymentTermsDays < 0 || paymentTermsDays > 365) throw new IllegalArgumentException("Betalningsvillkor ska vara 0–365 dagar.");
        if (defaultVatPercent.compareTo(BigDecimal.ZERO) < 0 || defaultVatPercent.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Standardskatt ska vara mellan 0 och 100 procent.");
        }
        defaultVatPercent = defaultVatPercent.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String required(String value, String message) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) throw new IllegalArgumentException(message);
        return cleaned;
    }

    private static String clean(String value) { return value == null ? "" : value.strip(); }
}
