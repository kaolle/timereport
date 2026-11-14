package com.timereport.subscription.configuration;

import com.timereport.subscription.application.port.in.SubscriptionAccess;
import com.timereport.subscription.application.port.in.SubscriptionPurchases;
import com.timereport.subscription.application.port.out.SubscriptionAccountEmail;
import com.timereport.subscription.application.port.out.SubscriptionBillingGateway;
import com.timereport.subscription.application.port.out.SubscriptionPurchaseRepository;
import com.timereport.subscription.application.port.out.SubscriptionRepository;
import com.timereport.subscription.application.port.out.SubscriptionPriceRepository;
import com.timereport.subscription.application.service.SubscriptionAccessService;
import com.timereport.subscription.application.service.SubscriptionPurchaseService;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

public class SubscriptionConfiguration {
    @Produces
    @Singleton
    SubscriptionAccess subscriptionAccess(SubscriptionRepository repository) {
        return new SubscriptionAccessService(repository);
    }

    @Produces
    @Singleton
    SubscriptionPurchases subscriptionPurchases(SubscriptionPurchaseRepository repository,
                                                SubscriptionBillingGateway billing, SubscriptionAccountEmail accounts,
                                                SubscriptionPriceRepository prices) {
        return new SubscriptionPurchaseService(repository, billing, accounts, prices, Clock.systemUTC());
    }
}
