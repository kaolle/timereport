package com.timereport.mail.domain;

import lombok.Builder;
import java.util.List;
import java.util.Objects;

@Builder
public record MailMessage(String from, String recipient, String replyTo, List<String> cc, List<String> bcc,
                          String subject, String text, List<MailAttachment> attachments) {
    public MailMessage {
        header(from);
        header(subject);
        recipient = address(recipient);
        replyTo = replyTo == null || replyTo.isBlank() ? null : address(replyTo);
        cc = cc == null ? List.of() : cc.stream().map(MailMessage::address).toList();
        bcc = bcc == null ? List.of() : bcc.stream().map(MailMessage::address).toList();
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        Objects.requireNonNull(text);
        if (cc.size() > 10 || bcc.size() > 10 || text.length() > 100_000 || attachments.size() > 5)
            throw new IllegalArgumentException("Mail exceeds size or recipient limit");
    }

    static void header(String value) {
        if (value == null || value.isBlank() || value.length() > 1000 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid mail header");
    }

    private static String address(String value) {
        if (value == null || value.length() > 254 || !value.matches("[^\\s@<>;,\"\\p{Cntrl}]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"))
            throw new IllegalArgumentException("Invalid mail address");
        return value;
    }

    @Override
    public String toString() {
        return "MailMessage[content redacted]";
    }
}
