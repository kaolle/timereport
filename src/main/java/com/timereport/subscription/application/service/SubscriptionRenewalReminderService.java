package com.timereport.subscription.application.service;

import com.timereport.subscription.application.port.in.SubscriptionRenewalDelivery;
import com.timereport.subscription.application.port.in.SubscriptionRenewalReminders;
import com.timereport.subscription.application.port.out.SubscriptionReminderMail;
import com.timereport.subscription.application.port.out.SubscriptionReminderRepository;
import com.timereport.subscription.domain.RenewalReminder;
import com.timereport.subscription.domain.RenewalReminderContext;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class SubscriptionRenewalReminderService implements SubscriptionRenewalReminders, SubscriptionRenewalDelivery {
    private final SubscriptionReminderRepository repository;
    private final SubscriptionReminderMail mail;

    public SubscriptionRenewalReminderService(SubscriptionReminderRepository repository, SubscriptionReminderMail mail) {
        this.repository = repository;
        this.mail = mail;
    }

    @Override
    public int sync(RenewalReminderContext context, Instant now) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(now);
        boolean eligible = context.renewing() && !context.ownerAccess()
                && context.renewalAt() != null && now.isBefore(context.renewalAt());
        var currentId = eligible ? RenewalReminder.idFor(context) : null;
        for (var pending : repository.pending(context.accountId())) {
            // Reconciliation includes historical subscriptions. A late event for
            // an old agreement must not cancel a newer agreement's reminder.
            if (!context.ownerAccess() && !pending.subscriptionId().equals(context.subscriptionId())) continue;
            if (!pending.id().equals(currentId)
                    || pending.state() == RenewalReminder.State.QUEUED
                    && pending.amountIncludingVat().compareTo(context.amountIncludingVat()) != 0) {
                cancel(pending, now);
            }
        }
        if (!eligible) return 0;
        var existing = repository.find(context.accountId(), currentId).orElse(null);
        if (existing != null && existing.state() != RenewalReminder.State.PLANNED
                && existing.state() != RenewalReminder.State.CANCELLED) return 0;
        int generation = existing == null ? 0 : existing.generation()
                + (existing.state() == RenewalReminder.State.CANCELLED ? 1 : 0);
        var reminder = RenewalReminder.builder().id(currentId).accountId(context.accountId())
                .subscriptionId(context.subscriptionId()).plan(context.plan())
                .renewalAt(context.renewalAt()).dueAt(RenewalReminder.dueAt(context.plan(), context.renewalAt()))
                .amountIncludingVat(context.amountIncludingVat()).state(RenewalReminder.State.PLANNED)
                .generation(generation).mailId(RenewalReminder.mailIdFor(currentId, generation)).build();
        repository.save(reminder, now);
        if (!reminder.due(now)) return 0;
        mail.queue(reminder, context.recipientEmail());
        repository.save(reminder.toBuilder().state(RenewalReminder.State.QUEUED).queuedAt(now).build(), now);
        return 1;
    }

    private void cancel(RenewalReminder reminder, Instant now) {
        // A message already being handed to SMTP cannot be retracted. Keep its
        // reference so completion cannot be overwritten or create a duplicate.
        if (reminder.state() == RenewalReminder.State.QUEUED && !mail.cancel(reminder)) return;
        repository.save(reminder.toBuilder().state(RenewalReminder.State.CANCELLED).completedAt(now).build(), now);
    }

    @Override
    public void completed(UUID accountId, UUID mailId, String state, Instant completedAt) {
        var terminal = RenewalReminder.State.valueOf(state);
        if (terminal == RenewalReminder.State.PLANNED || terminal == RenewalReminder.State.QUEUED)
            throw new IllegalArgumentException("Delivery result must be terminal");
        repository.completed(accountId, mailId, terminal, completedAt);
    }
}
