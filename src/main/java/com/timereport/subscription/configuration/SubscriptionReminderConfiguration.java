package com.timereport.subscription.configuration;

import com.timereport.subscription.application.port.out.SubscriptionReminderMail;
import com.timereport.subscription.application.port.out.SubscriptionReminderRepository;
import com.timereport.subscription.application.service.SubscriptionRenewalReminderService;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class SubscriptionReminderConfiguration {
    @Produces
    @Singleton
    SubscriptionRenewalReminderService subscriptionRenewalReminders(SubscriptionReminderRepository repository,
                                                                    SubscriptionReminderMail mail) {
        return new SubscriptionRenewalReminderService(repository, mail);
    }
}
