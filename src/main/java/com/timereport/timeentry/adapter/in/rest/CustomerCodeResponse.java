package com.timereport.timeentry.adapter.in.rest;

import com.timereport.timeentry.domain.RegisteredCustomerCode;
import lombok.Builder;

@Builder
public record CustomerCodeResponse(String code, boolean active, long version) {
    static CustomerCodeResponse from(RegisteredCustomerCode code) {
        return CustomerCodeResponse.builder().code(code.code().value()).active(code.active()).version(code.version()).build();
    }
}
