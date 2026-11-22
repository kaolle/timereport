package com.timereport.subscription.application.service;

import com.timereport.subscription.application.port.out.SubscriptionReminderMail;
import com.timereport.subscription.application.port.out.SubscriptionReminderRepository;
import com.timereport.subscription.domain.RenewalReminder;
import com.timereport.subscription.domain.RenewalReminderContext;
import com.timereport.subscription.domain.SubscriptionPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionRenewalReminderServiceTest {
    private final MemoryRepository repository = new MemoryRepository();
    private final SubscriptionReminderMail mail = mock(SubscriptionReminderMail.class);
    private final SubscriptionRenewalReminderService service = new SubscriptionRenewalReminderService(repository, mail);
    private final RenewalReminderContext context = RenewalReminderContext.builder().accountId(UUID.randomUUID())
            .subscriptionId("sub_reminder_test").plan(SubscriptionPlan.MONTHLY)
            .renewalAt(Instant.parse("2026-11-28T12:00:00Z")).amountIncludingVat(new BigDecimal("61.25"))
            .recipientEmail("customer@example.test").renewing(true).build();

    @Test
    void remindersUseStockholmNineOClockAndCalendarMonthsIncludingLeapYears() {
        assertEquals(Instant.parse("2026-11-21T08:00:00Z"),
                RenewalReminder.dueAt(SubscriptionPlan.MONTHLY, context.renewalAt()));
        assertEquals(Instant.parse("2028-02-29T08:00:00Z"),
                RenewalReminder.dueAt(SubscriptionPlan.YEARLY, Instant.parse("2028-03-31T12:00:00Z")));
        assertEquals(Instant.parse("2027-02-28T08:00:00Z"),
                RenewalReminder.dueAt(SubscriptionPlan.YEARLY, Instant.parse("2027-03-31T12:00:00Z")));
        assertEquals(Instant.parse("2027-03-29T07:00:00Z"),
                RenewalReminder.dueAt(SubscriptionPlan.MONTHLY, Instant.parse("2027-04-05T12:00:00Z")));
    }

    @Test
    void persistsFutureScheduleThenQueuesExactlyOnceWhenDue() {
        assertEquals(0, service.sync(context, Instant.parse("2026-11-20T12:00:00Z")));
        assertEquals(RenewalReminder.State.PLANNED, stored().state());
        verifyNoInteractions(mail);
        assertEquals(1, service.sync(context, Instant.parse("2026-11-21T08:00:00Z")));
        assertEquals(RenewalReminder.State.QUEUED, stored().state());
        assertEquals(0, service.sync(context, Instant.parse("2026-11-21T08:01:00Z")));
        verify(mail, times(1)).queue(any(), eq(context.recipientEmail()));
    }

    @Test
    void missedRunSendsBeforeRenewalButNeverAtOrAfterRenewal() {
        assertEquals(1, service.sync(context, context.renewalAt().minusSeconds(1)));
        when(mail.cancel(any())).thenReturn(true);
        assertEquals(0, service.sync(context, context.renewalAt()));
        assertEquals(RenewalReminder.State.CANCELLED, stored().state());
        verify(mail, times(1)).queue(any(), anyString());
        verify(mail).cancel(any());
    }

    @Test
    void ownerAndCancelledRenewalsNeverReceiveReminder() {
        var owner = context.toBuilder().ownerAccess(true).build();
        assertEquals(0, service.sync(owner, Instant.parse("2026-11-21T08:00:00Z")));
        var cancelled = context.toBuilder().renewing(false).renewalAt(null).amountIncludingVat(null).build();
        assertEquals(0, service.sync(cancelled, Instant.parse("2026-11-21T08:00:00Z")));
        assertTrue(repository.rows.isEmpty());
        verifyNoInteractions(mail);
    }

    @Test
    void cancellationRetractsOnlyItsOwnQueuedMailAndResumeGetsFreshReference() {
        Instant now = Instant.parse("2026-11-21T08:00:00Z");
        service.sync(context, now);
        var original = stored();
        when(mail.cancel(original)).thenReturn(true);
        service.sync(context.toBuilder().renewing(false).build(), now.plusSeconds(1));
        assertEquals(RenewalReminder.State.CANCELLED, stored().state());
        assertEquals(1, service.sync(context, now.plusSeconds(2)));
        assertEquals(original.id(), stored().id());
        assertNotEquals(original.mailId(), stored().mailId());
        assertEquals(1, stored().generation());
        verify(mail).cancel(original);
    }

    @Test
    void inFlightMailCannotBeRetractedOrQueuedAgain() {
        Instant now = Instant.parse("2026-11-21T08:00:00Z");
        service.sync(context, now);
        when(mail.cancel(any())).thenReturn(false);
        service.sync(context.toBuilder().renewing(false).build(), now.plusSeconds(1));
        assertEquals(RenewalReminder.State.QUEUED, stored().state());
        service.completed(context.accountId(), stored().mailId(), "SENT", now.plusSeconds(2));
        assertEquals(RenewalReminder.State.SENT, stored().state());
        assertEquals(0, service.sync(context, now.plusSeconds(3)));
        verify(mail, times(1)).queue(any(), anyString());
    }

    @Test
    void changedRenewalDateCancelsOldScheduleAndPlansNewOne() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        service.sync(context, now);
        UUID oldId = stored().id();
        var changed = context.toBuilder().renewalAt(Instant.parse("2026-12-28T12:00:00Z")).build();
        assertEquals(0, service.sync(changed, now.plusSeconds(1)));
        assertEquals(RenewalReminder.State.CANCELLED, repository.rows.get(oldId).state());
        assertEquals(RenewalReminder.State.PLANNED, repository.rows.get(RenewalReminder.idFor(changed)).state());
        verifyNoInteractions(mail);
    }

    @Test
    void priceChangeRetractsPendingMessageAndQueuesUpdatedAmount() {
        Instant now = Instant.parse("2026-11-21T08:00:00Z");
        service.sync(context, now);
        var original = stored();
        when(mail.cancel(original)).thenReturn(true);
        var changed = context.toBuilder().amountIncludingVat(new BigDecimal("74.00")).build();
        assertEquals(1, service.sync(changed, now.plusSeconds(1)));
        assertEquals(new BigDecimal("74.00"), stored().amountIncludingVat());
        assertNotEquals(original.mailId(), stored().mailId());
    }

    @Test
    void deliveryUpdatesAreAccountScopedAndCannotResendFailedTerminalMessage() {
        Instant now = Instant.parse("2026-11-21T08:00:00Z");
        service.sync(context, now);
        service.completed(UUID.randomUUID(), stored().mailId(), "SENT", now);
        assertEquals(RenewalReminder.State.QUEUED, stored().state());
        service.completed(context.accountId(), stored().mailId(), "FAILED", now);
        assertEquals(RenewalReminder.State.FAILED, stored().state());
        assertEquals(0, service.sync(context, now.plusSeconds(1)));
        verify(mail, times(1)).queue(any(), anyString());
    }

    @Test
    void historicalSubscriptionCannotCancelNewAgreementReminder() {
        Instant now = Instant.parse("2026-11-21T08:00:00Z");
        var newer = context.toBuilder().subscriptionId("sub_newer_reminder_test").build();
        service.sync(context, now);
        service.sync(newer, now);
        var oldReminder = stored();
        when(mail.cancel(oldReminder)).thenReturn(true);
        service.sync(context.toBuilder().renewing(false).build(), now.plusSeconds(1));
        assertEquals(RenewalReminder.State.CANCELLED, stored().state());
        assertEquals(RenewalReminder.State.QUEUED, repository.rows.get(RenewalReminder.idFor(newer)).state());
        verify(mail).cancel(oldReminder);
        verify(mail, times(1)).cancel(any());
    }

    private RenewalReminder stored() {
        return repository.rows.get(RenewalReminder.idFor(context));
    }

    private static class MemoryRepository implements SubscriptionReminderRepository {
        final LinkedHashMap<UUID, RenewalReminder> rows = new LinkedHashMap<>();
        public List<RenewalReminder> pending(UUID account) {
            return rows.values().stream().filter(row -> row.accountId().equals(account) && row.pending()).toList();
        }
        public Optional<RenewalReminder> find(UUID account, UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(row -> row.accountId().equals(account));
        }
        public void save(RenewalReminder reminder, Instant now) { rows.put(reminder.id(), reminder); }
        public void completed(UUID account, UUID mail, RenewalReminder.State state, Instant now) {
            rows.replaceAll((id, row) -> row.accountId().equals(account) && row.mailId().equals(mail)
                    && row.state() == RenewalReminder.State.QUEUED
                    ? row.toBuilder().state(state).completedAt(now).build() : row);
        }
    }
}
