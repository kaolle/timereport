package com.timereport.subscription.application.service;

public class SubscriptionBillingUnavailable extends RuntimeException {
    public SubscriptionBillingUnavailable(String message) {
        super(message);
    }

    public SubscriptionBillingUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
