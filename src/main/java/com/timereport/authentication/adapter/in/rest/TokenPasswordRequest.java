package com.timereport.authentication.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record TokenPasswordRequest(@NotBlank @Size(max = 128) String token,
                                   @NotBlank @Size(max = 128) String password) {
    @Override
    public String toString() {
        return "TokenPasswordRequest[redacted]";
    }

}
