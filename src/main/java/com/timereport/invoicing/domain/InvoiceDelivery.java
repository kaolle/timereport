package com.timereport.invoicing.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@Builder
public record InvoiceDelivery(UUID id, int invoiceNumber, String recipient, List<String> cc, List<String> bcc,
                              String message, State state,
                              Instant requestedAt, Instant completedAt) {
    public enum State {SENDING, SENT, FAILED}

    public InvoiceDelivery {
        Objects.requireNonNull(id);
        Objects.requireNonNull(state);
        Objects.requireNonNull(requestedAt);
        message = message == null ? "" : message.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (message.length() > 2000)
            throw new IllegalArgumentException("Meddelandet får innehålla högst 2000 tecken.");
        recipient = email(recipient);
        String primary = recipient;
        cc = addresses(cc).stream().filter(address -> !address.equalsIgnoreCase(primary)).toList();
        List<String> visible = cc;
        bcc = addresses(bcc).stream().filter(address -> !address.equalsIgnoreCase(primary)
                && visible.stream().noneMatch(copy -> copy.equalsIgnoreCase(address))).toList();
        if (invoiceNumber < 1) throw new IllegalArgumentException("Fakturanumret måste vara positivt.");
        if (state != State.SENDING && completedAt == null)
            throw new IllegalArgumentException("Utskicket saknar sluttid.");
    }

    public static String email(String value) {
        String address = value == null ? "" : value.strip();
        if (address.length() > 254 || !address.matches("[^\\s@<>;,\"\\p{Cntrl}]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"))
            throw new IllegalArgumentException("Ange en giltig e-postadress utan visningsnamn.");
        return address;
    }

    public InvoiceDelivery complete(State result, Instant now) {
        if (result == State.SENDING) throw new IllegalArgumentException("Ogiltigt utskicksresultat.");
        return new InvoiceDelivery(id, invoiceNumber, recipient, cc, bcc, message, result, requestedAt, now);
    }

    public static List<String> addresses(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 10) throw new IllegalArgumentException("Ange högst tio adresser i varje kopielista.");
        var addresses = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (String value : values) {
            String address = email(value);
            addresses.putIfAbsent(address, address);
        }
        return List.copyOf(addresses.values());
    }
}
