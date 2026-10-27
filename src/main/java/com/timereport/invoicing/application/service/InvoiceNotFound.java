package com.timereport.invoicing.application.service;

public class InvoiceNotFound extends RuntimeException {
    public InvoiceNotFound() {
        super("Fakturan finns inte.");
    }
}
