package com.timereport.invoicing.domain;

import lombok.Builder;

@Builder
public record InvoiceNumbering(int nextNumber) {
    public InvoiceNumbering {
        if (nextNumber < 1 || nextNumber > 999_999_999) {
            throw new IllegalArgumentException("Nästa fakturanummer ska vara mellan 1 och 999999999.");
        }
    }
}
