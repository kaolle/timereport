package com.timereport.subscription.configuration;

import com.timereport.subscription.application.port.in.SubscriptionRenewalReminders;
import com.timereport.subscription.application.port.out.SubscriptionAccountEmail;
import com.timereport.subscription.application.port.out.SubscriptionLifecycleGateway;
import com.timereport.subscription.application.port.out.SubscriptionLifecycleRepository;
import com.timereport.subscription.application.port.out.SubscriptionPriceRepository;
import com.timereport.subscription.application.service.SubscriptionLifecycleService;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;

public class SubscriptionLifecycleConfiguration {
    @Produces
    @Singleton
    SubscriptionLifecycleService lifecycle(SubscriptionLifecycleRepository repository,
                                            SubscriptionLifecycleGateway gateway,
                                            SubscriptionPriceRepository prices, SubscriptionAccountEmail accounts,
                                            SubscriptionRenewalReminders reminders,
                                            @ConfigProperty(name = "subscription.grace-days") int graceDays) {
        return new SubscriptionLifecycleService(repository, gateway, prices, accounts, reminders,
                Clock.systemUTC(), Duration.ofDays(graceDays));
    }
}
