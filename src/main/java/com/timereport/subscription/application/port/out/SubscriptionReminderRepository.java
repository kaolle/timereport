package com.timereport.subscription.application.port.out;

import com.timereport.subscription.domain.RenewalReminder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionReminderRepository {
    List<RenewalReminder> pending(UUID accountId);
    Optional<RenewalReminder> find(UUID accountId, UUID reminderId);
    void save(RenewalReminder reminder, Instant now);
    void completed(UUID accountId, UUID mailId, RenewalReminder.State state, Instant completedAt);
}
