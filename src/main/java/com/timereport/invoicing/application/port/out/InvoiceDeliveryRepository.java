package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.InvoiceDelivery;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceDeliveryRepository {
    boolean start(InvoiceDelivery delivery);

    Optional<InvoiceDelivery> find(UUID requestId);

    void complete(InvoiceDelivery delivery);
}
