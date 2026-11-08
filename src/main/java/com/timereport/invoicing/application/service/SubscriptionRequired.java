package com.timereport.invoicing.application.service;

public class SubscriptionRequired extends RuntimeException {
    public SubscriptionRequired() {
        super("Fakturering kräver ett aktivt abonnemang.");
    }
}
