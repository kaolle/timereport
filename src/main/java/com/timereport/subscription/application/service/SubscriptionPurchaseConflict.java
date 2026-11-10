package com.timereport.subscription.application.service;

public class SubscriptionPurchaseConflict extends RuntimeException {
    public SubscriptionPurchaseConflict(String message) {
        super(message);
    }
}
