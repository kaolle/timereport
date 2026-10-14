package com.timereport.authentication.adapter.in.rest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record CredentialsRequest(@NotBlank @Email @Size(max = 254) String email,
                                 @NotBlank @Size(max = 128) String password) {
    @Override
    public String toString() {
        return "CredentialsRequest[redacted]";
    }

}
