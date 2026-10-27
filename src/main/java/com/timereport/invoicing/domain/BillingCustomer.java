package com.timereport.invoicing.domain;

import lombok.Builder;

import java.util.Objects;
import java.util.UUID;

@Builder(toBuilder = true)
public record BillingCustomer(UUID id, String customerNumber, String name, String shortName, String addressLine, String postalCode,
                             String city, String country, String organizationNumber, String vatNumber,
                              String invoiceEmail, String customerReference, String ourReference, int paymentTermsDays,
                              String contactEmail) {
    public BillingCustomer {
        Objects.requireNonNull(id, "Kund-ID krävs.");
        customerNumber = required(customerNumber, "Kundnummer krävs.");
        name = required(name, "Kundnamn krävs.");
        shortName = clean(shortName);
        if (shortName.length() > 40) throw new IllegalArgumentException("Kortnamnet får vara högst 40 tecken.");
        addressLine = required(addressLine, "Kundadress krävs.");
        postalCode = required(postalCode, "Kundens postnummer krävs.");
        city = required(city, "Kundens ort krävs.");
        country = required(country, "Kundens land krävs.");
        organizationNumber = clean(organizationNumber);
        vatNumber = clean(vatNumber);
        invoiceEmail = clean(invoiceEmail);
        contactEmail = clean(contactEmail);
        if (!contactEmail.isBlank()) contactEmail = InvoiceDelivery.email(contactEmail);
        customerReference = clean(customerReference);
        ourReference = clean(ourReference);
        if (paymentTermsDays < 0 || paymentTermsDays > 365) throw new IllegalArgumentException("Betalningsvillkor ska vara 0–365 dagar.");
    }

    private static String required(String value, String message) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) throw new IllegalArgumentException(message);
        return cleaned;
    }

    private static String clean(String value) { return value == null ? "" : value.strip(); }
}
