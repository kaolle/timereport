package com.timereport.timeentry.domain;

import java.util.Objects;
import lombok.Builder;

@Builder(toBuilder = true)
public record RegisteredCustomerCode(CustomerCode code, boolean active, long version) {
    public RegisteredCustomerCode {
        Objects.requireNonNull(code, "Customer code is required");
        if (version < 1) {
            throw new IllegalArgumentException("Version must be positive");
        }
    }
}
