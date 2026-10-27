package com.timereport.invoicing.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Builder
public record InvoiceDelivery(UUID id, int invoiceNumber, String recipient, State state,
                              Instant requestedAt, Instant completedAt) {
    public enum State {SENDING, SENT, FAILED}

    public InvoiceDelivery {
        Objects.requireNonNull(id);
        Objects.requireNonNull(state);
        Objects.requireNonNull(requestedAt);
        recipient = email(recipient);
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
        return new InvoiceDelivery(id, invoiceNumber, recipient, result, requestedAt, now);
    }
}
