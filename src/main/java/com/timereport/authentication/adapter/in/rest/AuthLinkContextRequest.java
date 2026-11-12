package com.timereport.authentication.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record AuthLinkContextRequest(@NotBlank @Size(max = 128) String token,
                                     @NotBlank @Pattern(regexp = "verify|reset") String purpose) {
    @Override
    public String toString() {
        return "AuthLinkContextRequest[redacted]";
    }
}
