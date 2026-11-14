package com.timereport.subscription.configuration;

import com.timereport.subscription.application.port.in.SubscriptionPricing;
import com.timereport.subscription.application.port.out.SubscriptionPriceProvisioner;
import com.timereport.subscription.application.port.out.SubscriptionPriceRepository;
import com.timereport.subscription.application.service.SubscriptionPricingService;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

public class SubscriptionPricingConfiguration {
    @Produces
    @Singleton
    public SubscriptionPricing pricing(SubscriptionPriceRepository repository, SubscriptionPriceProvisioner stripe) {
        return new SubscriptionPricingService(repository, stripe, Clock.systemUTC());
    }
}
