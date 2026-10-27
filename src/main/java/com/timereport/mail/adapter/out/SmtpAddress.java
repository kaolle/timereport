package com.timereport.mail.adapter.out;

public final class SmtpAddress {
    private SmtpAddress() {
    }

    public static String named(String displayName, String configuredAddress) {
        String email = configuredAddress.strip();
        if (email.contains("<") && email.endsWith(">"))
            email = email.substring(email.lastIndexOf('<') + 1, email.length() - 1).strip();
        if (email.chars().anyMatch(Character::isISOControl) || email.contains("<") || email.contains(">") || !email.contains("@"))
            throw new IllegalArgumentException("SMTP-avsändaradressen är ogiltig.");
        String name = displayName.replaceAll("[\\p{Cntrl}]", " ").replace("\\", "\\\\").replace("\"", "\\\"").strip();
        return "\"" + name + "\" <" + email + ">";
    }
}
