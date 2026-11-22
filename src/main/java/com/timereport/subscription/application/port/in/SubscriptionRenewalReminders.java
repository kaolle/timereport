package com.timereport.subscription.application.port.in;

import com.timereport.subscription.domain.RenewalReminderContext;
import java.time.Instant;

public interface SubscriptionRenewalReminders {
    /** Synchronize under the caller's account lock and transaction; returns newly queued messages. */
    int sync(RenewalReminderContext context, Instant now);
}
