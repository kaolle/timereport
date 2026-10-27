package com.timereport.invoicing.adapter.in.rest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record SendInvoiceRequest(@NotBlank @Email @Size(max = 254) String recipient, @NotNull UUID requestId,
                                 @Size(max = 10) List<@NotBlank @Email @Size(max = 254) String> cc,
                                 @Size(max = 10) List<@NotBlank @Email @Size(max = 254) String> bcc) {
    public SendInvoiceRequest {
        cc = cc == null ? null : List.copyOf(cc);
        bcc = bcc == null ? null : List.copyOf(bcc);
    }
}
