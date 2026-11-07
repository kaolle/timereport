package com.timereport.mail.domain;

import lombok.Builder;
import java.util.Objects;
import java.util.UUID;

@Builder
public record MailReference(UUID accountId, String type, UUID id) {
    public MailReference {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(id);
        if (type == null || !type.matches("[a-z-]{1,40}")) throw new IllegalArgumentException("Invalid mail type");
    }
}
