package com.timereport.invoicing.application.service;

public class InvoicingConflict extends RuntimeException {
    public InvoicingConflict(String message) { super(message); }
}
