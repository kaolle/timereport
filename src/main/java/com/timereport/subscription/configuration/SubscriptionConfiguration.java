package com.timereport.subscription.configuration;

import com.timereport.subscription.application.port.in.SubscriptionAccess;
import com.timereport.subscription.application.port.out.SubscriptionRepository;
import com.timereport.subscription.application.service.SubscriptionAccessService;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class SubscriptionConfiguration {
    @Produces
    @Singleton
    SubscriptionAccess subscriptionAccess(SubscriptionRepository repository) {
        return new SubscriptionAccessService(repository);
    }
}
