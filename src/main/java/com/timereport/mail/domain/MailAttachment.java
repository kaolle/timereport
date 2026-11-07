package com.timereport.mail.domain;

import lombok.Builder;
import java.util.Base64;
import java.util.Objects;

@Builder
public record MailAttachment(String name, String contentType, String base64) {
    public MailAttachment {
        MailMessage.header(name);
        MailMessage.header(contentType);
        Objects.requireNonNull(base64);
        if (base64.length() > 12_000_000) throw new IllegalArgumentException("Mail attachment too large");
        Base64.getDecoder().decode(base64);
    }

    public static MailAttachment of(String name, String contentType, byte[] bytes) {
        return new MailAttachment(name, contentType, Base64.getEncoder().encodeToString(bytes));
    }

    public byte[] bytes() {
        return Base64.getDecoder().decode(base64);
    }

    @Override
    public String toString() {
        return "MailAttachment[content redacted]";
    }
}
