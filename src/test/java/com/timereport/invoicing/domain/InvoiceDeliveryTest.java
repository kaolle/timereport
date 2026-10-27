package com.timereport.invoicing.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceDeliveryTest {
    @Test void copiesAreValidatedImmutableAndNotDuplicatedAcrossToCcAndBcc() {
        var copies = new ArrayList<>(List.of("contact@example.test", "CONTACT@example.test", "to@example.test"));
        var delivery = InvoiceDelivery.builder().id(UUID.randomUUID()).invoiceNumber(1).recipient("to@example.test")
                .cc(copies).bcc(List.of("contact@example.test", "company@example.test", "to@example.test"))
                .state(InvoiceDelivery.State.SENDING).requestedAt(Instant.now()).build();
        copies.clear();
        assertEquals(List.of("contact@example.test"), delivery.cc());
        assertEquals(List.of("company@example.test"), delivery.bcc());
        assertThrows(UnsupportedOperationException.class, () -> delivery.bcc().add("extra@example.test"));
        assertEquals(delivery.cc(), delivery.complete(InvoiceDelivery.State.SENT, Instant.now()).cc());
        assertThrows(IllegalArgumentException.class, () -> InvoiceDelivery.addresses(List.of("bad\r\nBcc:evil@example.test")));
        assertThrows(IllegalArgumentException.class, () -> InvoiceDelivery.addresses(Collections.nCopies(11, "same@example.test")));
    }
}
