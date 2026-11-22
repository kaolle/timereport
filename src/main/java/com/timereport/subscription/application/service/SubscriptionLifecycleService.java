package com.timereport.subscription.application.service;

import com.timereport.subscription.application.port.in.MaintainSubscriptions;
import com.timereport.subscription.application.port.in.ProcessSubscriptionEvents;
import com.timereport.subscription.application.port.in.SubscriptionRenewalReminders;
import com.timereport.subscription.application.port.out.*;
import com.timereport.subscription.domain.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

/** Coordinates authoritative billing reads inside the same account lock as local writes. */
public class SubscriptionLifecycleService implements ProcessSubscriptionEvents, MaintainSubscriptions {
    private static final System.Logger LOG = System.getLogger(SubscriptionLifecycleService.class.getName());
    private final SubscriptionLifecycleRepository repository;
    private final SubscriptionLifecycleGateway billing;
    private final SubscriptionPriceRepository prices;
    private final SubscriptionAccountEmail accounts;
    private final SubscriptionRenewalReminders reminders;
    private final Clock clock;
    private final Duration gracePeriod;

    public SubscriptionLifecycleService(SubscriptionLifecycleRepository repository, SubscriptionLifecycleGateway billing,
                                        SubscriptionPriceRepository prices, SubscriptionAccountEmail accounts,
                                        SubscriptionRenewalReminders reminders, Clock clock, Duration gracePeriod) {
        this.repository = Objects.requireNonNull(repository);
        this.billing = Objects.requireNonNull(billing);
        this.prices = Objects.requireNonNull(prices);
        this.accounts = Objects.requireNonNull(accounts);
        this.reminders = Objects.requireNonNull(reminders);
        this.clock = Objects.requireNonNull(clock);
        this.gracePeriod = Objects.requireNonNull(gracePeriod);
        if (gracePeriod.isNegative() || gracePeriod.isZero() || gracePeriod.compareTo(Duration.ofDays(30)) > 0)
            throw new IllegalArgumentException("Invalid subscription grace period");
    }

    @Override
    public BillingEventResult process(BillingEvent event) {
        Objects.requireNonNull(event);
        var link = billing.locate(event);
        if (link == null || link.customerId() == null) return BillingEventResult.IGNORED;
        var account = repository.accountForCustomer(link.customerId());
        if (account.isEmpty()) return BillingEventResult.IGNORED;
        return repository.locked(account.get(), () -> {
            if (!repository.beginEvent(event)) return BillingEventResult.DUPLICATE;
            var now = clock.instant();
            BillingEventResult result;
            if (link.reviewKind() != null) {
                alert(account.get(), event.id(), link.reviewKind(), link.subscriptionId(), now);
                result = BillingEventResult.PROCESSED;
            } else if (link.subscriptionId() != null) {
                var remote = billing.fetch(link.subscriptionId());
                if (!link.customerId().equals(remote.customerId()))
                    throw new SubscriptionBillingUnavailable("Betalningshändelsens kundkoppling stämmer inte.");
                result = synchronize(account.get(), remote).managed()
                        ? BillingEventResult.PROCESSED : BillingEventResult.IGNORED;
            } else {
                result = BillingEventResult.IGNORED;
            }
            repository.completeEvent(event.id(), now);
            return result;
        });
    }

    @Override
    public MaintenanceResult run() {
        int checked = 0, failed = 0, queued = 0, terminated = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        // The durable least-recently-checked order also advances failed accounts.
        // A request that reaches its time budget therefore cannot starve later UUIDs.
        for (UUID accountId : repository.maintenanceCandidates(null, 50)) {
            if (checked > 0 && System.nanoTime() >= deadline) break;
            checked++;
            boolean accountFailed = false;
            try {
                var outcome = repository.locked(accountId, () -> synchronizeAccount(accountId));
                queued += outcome.reminders();
                terminated += outcome.terminated();
            } catch (RuntimeException failure) {
                accountFailed = true;
                // SDK exceptions may contain customer data or hosted URLs; log identifiers only.
                LOG.log(System.Logger.Level.ERROR, "Subscription reconciliation failed for account {0}; category={1}",
                        accountId, failure.getClass().getSimpleName());
            } finally {
                try {
                    repository.markMaintenanceChecked(accountId, clock.instant());
                } catch (RuntimeException failure) {
                    accountFailed = true;
                    LOG.log(System.Logger.Level.ERROR, "Subscription maintenance cursor failed for account {0}", accountId);
                }
            }
            if (accountFailed) failed++;
        }
        LOG.log(System.Logger.Level.INFO,
                "Subscription maintenance checked={0}, failed={1}, remindersQueued={2}, terminated={3}",
                checked, failed, queued, terminated);
        return new MaintenanceResult(checked, failed, queued, terminated);
    }

    private AccountOutcome synchronizeAccount(UUID accountId) {
        var customer = repository.customerId(accountId);
        if (customer.isEmpty()) return new AccountOutcome(0, 0);
        var ids = new LinkedHashSet<>(repository.subscriptionIds(accountId));
        ids.addAll(billing.subscriptionIds(customer.get()));
        int queued = 0, terminated = 0;
        for (var id : ids) {
            var remote = billing.fetch(id);
            if (!customer.get().equals(remote.customerId()))
                throw new SubscriptionBillingUnavailable("Abonnemangets kundkoppling stämmer inte.");
            var outcome = synchronize(accountId, remote);
            queued += outcome.reminders();
            if (outcome.terminated()) terminated++;
        }
        return new AccountOutcome(queued, terminated);
    }

    private SyncOutcome synchronize(UUID accountId, BillingSubscription remote) {
        var now = clock.instant();
        var model = managedPrice(accountId, remote);
        if (model == null) {
            alert(accountId, remote.id(), "UNMANAGED_SUBSCRIPTION", remote.id(), now);
            return new SyncOutcome(false, 0, false);
        }
        var previous = repository.agreement(accountId, remote.id()).orElse(null);
        if (previous == null) {
            for (var otherId : repository.subscriptionIds(accountId)) {
                var other = repository.agreement(accountId, otherId).orElse(null);
                if (other != null && !other.terminal() && other.state() != SubscriptionState.CANCELED) {
                    alert(accountId, remote.id(), "CONCURRENT_SUBSCRIPTIONS", remote.id(), now);
                    return new SyncOutcome(false, 0, false);
                }
            }
        }
        if (previous != null && previous.terminal()) {
            // A terminal agreement is a tombstone. Delayed invoices never resurrect it.
            if (remote.state() != BillingSubscription.State.CANCELED)
                alert(accountId, remote.id(), "TERMINAL_SUBSCRIPTION_CHANGED", remote.id(), now);
            for (var invoice : remote.invoices()) {
                if (invoice.state() == BillingInvoice.State.PAID && invoice.paidAt().isAfter(previous.endedAt()))
                    alert(accountId, invoice.id(), "PAYMENT_AFTER_TERMINATION", remote.id(), now);
            }
            int queued = synchronizeReminders(accountId, remote, model, false, now);
            repository.resolveCheckout(accountId, remote.checkoutAttemptId(), remote.id(), now);
            return new SyncOutcome(true, queued, false);
        }

        UUID agreementId = previous == null ? UUID.randomUUID() : previous.id();
        if (previous == null) {
            // The paid-period foreign key belongs to this verified agreement. Both writes
            // commit together; a failure never leaves a partially adopted subscription.
            repository.saveAgreement(SubscriptionAgreement.builder().id(agreementId).accountId(accountId)
                    .subscriptionId(remote.id()).priceModelId(model.id()).plan(model.plan())
                    .state(SubscriptionState.PENDING).cancelAtPeriodEnd(remote.cancelAtPeriodEnd())
                    .periodStart(remote.periodStart()).periodEnd(remote.periodEnd()).updatedAt(now).build());
        }

        boolean lateRenewal = false;
        for (var invoice : remote.invoices()) {
            if (!remote.id().equals(invoice.subscriptionId()) || !remote.customerId().equals(invoice.customerId()))
                throw new SubscriptionBillingUnavailable("Fakturan hör inte till abonnemanget.");
            if (invoice.state() != BillingInvoice.State.PAID) continue;
            if (!remote.priceId().equals(invoice.priceId()))
                throw new SubscriptionBillingUnavailable("Betald faktura har ett annat abonnemangspris.");
            if (remote.state() == BillingSubscription.State.CANCELED && remote.endedAt() != null
                    && invoice.paidAt().isAfter(remote.endedAt())) {
                alert(accountId, invoice.id(), "PAYMENT_AFTER_TERMINATION", remote.id(), now);
                continue;
            }
            Instant cutoff = invoice.periodStart().plus(gracePeriod);
            if (invoice.renewal() && !invoice.paidAt().isBefore(cutoff)) {
                lateRenewal = true;
                alert(accountId, invoice.id(), "LATE_RENEWAL_PAYMENT", remote.id(), now);
                continue;
            }
            if (previous != null && previous.graceUntil() != null && invoice.renewal()
                    && !invoice.periodEnd().isBefore(previous.graceUntil())
                    && !invoice.paidAt().isBefore(previous.graceUntil())) {
                lateRenewal = true;
                alert(accountId, invoice.id(), "LATE_RENEWAL_PAYMENT", remote.id(), now);
                continue;
            }
            repository.recordPaidPeriod(accountId, remote.id(), invoice);
        }
        Instant paidUntil = repository.paidUntil(accountId, remote.id()).orElse(null);
        boolean overdue = remote.state() == BillingSubscription.State.PAST_DUE
                || remote.state() == BillingSubscription.State.UNPAID;
        Instant graceUntil = overdue && paidUntil != null ? paidUntil.plus(gracePeriod) : null;
        // Keep the originally established deadline. Retries cannot extend it.
        if (overdue && previous != null && previous.graceUntil() != null)
            graceUntil = graceUntil == null || previous.graceUntil().isBefore(graceUntil)
                    ? previous.graceUntil() : graceUntil;
        boolean expiredGrace = graceUntil != null && !now.isBefore(graceUntil);
        boolean unfinishedTermination = previous != null && previous.state() == SubscriptionState.PAST_DUE
                && previous.graceUntil() != null && !now.isBefore(previous.graceUntil())
                && (paidUntil == null || !paidUntil.isAfter(previous.graceUntil()));
        boolean terminate = lateRenewal || expiredGrace || unfinishedTermination;
        String terminalReason = null;
        Instant endedAt = null;
        SubscriptionState state;
        if (terminate) {
            // Remote operations are idempotent; if a later operation fails, a retry finishes cleanup.
            billing.terminate(remote.id(), "timereport-lapse-" + remote.id());
            state = SubscriptionState.CANCELED;
            terminalReason = lateRenewal ? "LATE_RENEWAL_PAYMENT" : "GRACE_EXPIRED";
            endedAt = now;
            graceUntil = null;
        } else if (remote.state() == BillingSubscription.State.CANCELED) {
            state = SubscriptionState.CANCELED;
            terminalReason = "CANCELED";
            endedAt = remote.endedAt() == null ? now : remote.endedAt();
            graceUntil = null;
        } else if (overdue && paidUntil != null) {
            state = SubscriptionState.PAST_DUE;
        } else if (remote.state() == BillingSubscription.State.ACTIVE && paidUntil != null) {
            state = SubscriptionState.ACTIVE;
        } else {
            state = SubscriptionState.PENDING;
            graceUntil = null;
        }
        var agreement = SubscriptionAgreement.builder()
                .id(agreementId).accountId(accountId)
                .subscriptionId(remote.id()).priceModelId(model.id()).plan(model.plan()).state(state)
                .graceUntil(graceUntil).cancelAtPeriodEnd(remote.cancelAtPeriodEnd())
                .periodStart(remote.periodStart()).periodEnd(remote.periodEnd())
                .endedAt(endedAt).terminalReason(terminalReason).updatedAt(now).build();
        repository.saveAgreement(agreement);
        repository.resolveCheckout(accountId, remote.checkoutAttemptId(), remote.id(), now);
        int queued = synchronizeReminders(accountId, remote, model,
                state == SubscriptionState.ACTIVE && !remote.cancelAtPeriodEnd() && !agreement.terminal(), now);
        return new SyncOutcome(true, queued, terminate);
    }

    private SubscriptionPriceModel managedPrice(UUID accountId, BillingSubscription remote) {
        if (!accountId.equals(remote.accountId()) || remote.priceModelId() == null
                || remote.checkoutAttemptId() == null || remote.checkoutSessionId() == null
                || remote.priceId() == null || remote.plan() == null) return null;
        var binding = repository.binding(accountId, remote.checkoutAttemptId(), remote.checkoutSessionId()).orElse(null);
        if (binding == null || !binding.catalogPricing() || !remote.priceModelId().equals(binding.priceModelId())
                || !remote.priceId().equals(binding.priceId())) return null;
        var model = prices.find(remote.priceModelId()).orElse(null);
        return model != null && model.state() == SubscriptionPriceModel.State.READY
                && model.plan() == remote.plan() && remote.priceId().equals(model.stripe().priceId()) ? model : null;
    }

    private int synchronizeReminders(UUID accountId, BillingSubscription remote, SubscriptionPriceModel model,
                                     boolean renewing, Instant now) {
        BigDecimal gross = model.vatInclusive() ? model.amount() : model.amount()
                .multiply(BigDecimal.ONE.add(model.vatPercent().movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
        var context = RenewalReminderContext.builder().accountId(accountId).subscriptionId(remote.id())
                .plan(model.plan()).renewalAt(remote.periodEnd()).amountIncludingVat(gross)
                .recipientEmail(accounts.email(accountId)).renewing(renewing)
                .ownerAccess(repository.ownerAccess(accountId)).build();
        return reminders.sync(context, now);
    }

    private void alert(UUID accountId, String reference, String kind, String subscriptionId, Instant now) {
        repository.alert(accountId, reference, kind, subscriptionId, now);
        LOG.log(System.Logger.Level.WARNING, "Subscription billing review required: account={0}, kind={1}, reference={2}",
                accountId, kind, reference);
    }

    private record SyncOutcome(boolean managed, int reminders, boolean terminated) {}
    private record AccountOutcome(int reminders, int terminated) {}
}
