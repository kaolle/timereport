package com.timereport.subscription.application.service;

import com.timereport.subscription.application.port.in.SubscriptionPurchases;
import com.timereport.subscription.application.port.out.SubscriptionAccountEmail;
import com.timereport.subscription.application.port.out.SubscriptionBillingGateway;
import com.timereport.subscription.application.port.out.SubscriptionPurchaseRepository;
import com.timereport.subscription.domain.CheckoutAttempt;
import com.timereport.subscription.domain.HostedCheckout;
import com.timereport.subscription.domain.SubscriptionPlan;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public class SubscriptionPurchaseService implements SubscriptionPurchases {
    private final SubscriptionPurchaseRepository repository;
    private final SubscriptionBillingGateway billing;
    private final SubscriptionAccountEmail accounts;
    private final Clock clock;

    public SubscriptionPurchaseService(SubscriptionPurchaseRepository repository, SubscriptionBillingGateway billing,
                                       SubscriptionAccountEmail accounts, Clock clock) {
        this.repository = repository;
        this.billing = billing;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override
    public HostedCheckout checkout(UUID accountId, SubscriptionPlan plan) {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(plan, "plan is required");
        billing.requireCheckoutConfigured();
        var attempt = repository.reserve(accountId, plan, clock.instant());
        if (attempt.state() == CheckoutAttempt.State.OPEN) {
            var existing = billing.retrieveCheckout(attempt.stripeSessionId());
            if (!existing.customerId().equals(repository.customerId(accountId).orElse(null)))
                throw new SubscriptionBillingUnavailable("Köpets kundkoppling kunde inte verifieras.");
            if (existing.state() == HostedCheckout.State.OPEN) {
                if (attempt.plan() != plan)
                    throw new SubscriptionPurchaseConflict("Ett annat köp pågår redan för kontot.");
                return existing;
            }
            if (existing.state() == HostedCheckout.State.COMPLETE) {
                repository.complete(attempt.id());
                throw new SubscriptionPurchaseConflict("Betalningen behandlas. Vänta på bekräftelse innan du försöker igen.");
            }
            repository.expire(attempt.id());
            attempt = repository.reserve(accountId, plan, clock.instant());
        }
        if (attempt.plan() != plan)
            throw new SubscriptionPurchaseConflict("Ett annat köp pågår redan för kontot.");
        if (attempt.state() != CheckoutAttempt.State.CREATING)
            throw new SubscriptionPurchaseConflict("Ett annat köp pågår redan för kontot.");
        var customer = repository.customerId(accountId).orElseGet(() ->
                repository.saveCustomerId(accountId, billing.createCustomer(accountId, accounts.email(accountId),
                        repository.customerIdempotencyKey(accountId))));
        var session = billing.createCheckout(accountId, attempt.id(), customer, plan, attempt.idempotencyKey());
        if (session.state() != HostedCheckout.State.OPEN || !customer.equals(session.customerId()))
            throw new SubscriptionBillingUnavailable("Stripe returnerade ingen giltig köpsession.");
        repository.open(attempt.id(), session.sessionId(), session.expiresAt());
        return session;
    }

    @Override
    public String portal(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId is required");
        billing.requirePortalConfigured();
        var customer = repository.customerId(accountId).orElseThrow(() ->
                new SubscriptionPurchaseConflict("Kontot har ännu inget abonnemang att hantera."));
        return billing.createPortal(customer);
    }
}
