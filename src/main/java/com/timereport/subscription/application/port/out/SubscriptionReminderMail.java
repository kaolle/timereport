package com.timereport.subscription.application.port.out;

import com.timereport.subscription.domain.RenewalReminder;

public interface SubscriptionReminderMail {
    void queue(RenewalReminder reminder, String recipientEmail);
    boolean cancel(RenewalReminder reminder);
}
