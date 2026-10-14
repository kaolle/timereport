package com.timereport.authentication.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                    @NotBlank @Size(max = 128) String newPassword) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }

}
