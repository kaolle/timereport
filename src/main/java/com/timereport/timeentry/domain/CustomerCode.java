package com.timereport.timeentry.domain;

import lombok.Builder;

/** A customer's reporting code. Membership in the configured code list is checked by the application. */
@Builder(toBuilder = true)
public record CustomerCode(String value) {
    public CustomerCode {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Customer code must not be blank");
        }
        value = value.strip();
    }
}
