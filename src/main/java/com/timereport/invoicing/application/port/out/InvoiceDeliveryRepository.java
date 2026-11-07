package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.InvoiceDelivery;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface InvoiceDeliveryRepository {
    boolean start(InvoiceDelivery delivery);

    Optional<InvoiceDelivery> find(UUID requestId);

    void completeFromMail(UUID accountId, UUID requestId, boolean accepted, Instant completedAt);
}
