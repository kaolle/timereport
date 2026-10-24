package com.timereport.invoicing.application.port.out;

import java.util.function.Supplier;

public interface InvoiceTransaction {
    <T> T execute(Supplier<T> work);
}
