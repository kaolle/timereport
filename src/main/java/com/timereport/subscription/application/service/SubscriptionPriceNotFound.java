package com.timereport.subscription.application.service;

public class SubscriptionPriceNotFound extends RuntimeException {
    public SubscriptionPriceNotFound() {
        super("Prismodellen finns inte.");
    }
}
