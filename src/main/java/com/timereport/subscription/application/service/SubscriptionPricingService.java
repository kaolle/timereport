package com.timereport.subscription.application.service;

import com.timereport.subscription.application.port.in.SubscriptionPricing;
import com.timereport.subscription.application.port.out.SubscriptionPriceProvisioner;
import com.timereport.subscription.application.port.out.SubscriptionPriceRepository;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class SubscriptionPricingService implements SubscriptionPricing {
    private final SubscriptionPriceRepository repository;
    private final SubscriptionPriceProvisioner stripe;
    private final Clock clock;

    public SubscriptionPricingService(SubscriptionPriceRepository repository, SubscriptionPriceProvisioner stripe,
                                      Clock clock) {
        this.repository = repository;
        this.stripe = stripe;
        this.clock = clock;
    }

    @Override
    public List<SubscriptionPriceModel> list() {
        return List.copyOf(repository.list());
    }

    @Override
    public SubscriptionPriceModel create(UUID actorId, UUID requestId, String name, SubscriptionPlan plan,
                                         BigDecimal amount, BigDecimal vatPercent, boolean vatInclusive) {
        var draft = SubscriptionPriceModel.builder().id(requestId).name(name).plan(plan).amount(amount)
                .vatPercent(vatPercent).vatInclusive(vatInclusive).state(SubscriptionPriceModel.State.CREATING)
                .createdBy(actorId).createdAt(clock.instant()).build();
        var reserved = repository.reserve(draft);
        if (reserved.state() == SubscriptionPriceModel.State.READY) return reserved;
        if (!reserved.createdAt().isAfter(clock.instant().minus(Duration.ofHours(23))))
            throw new SubscriptionPurchaseConflict("Prisupplägget är för gammalt för ett säkert återförsök. Kontrollera vad som skapats i Stripe innan du lägger upp ett nytt pris.");
        // reserve commits before the network request, preserving the idempotency
        // reference even if Stripe responds but this process stops before completion.
        repository.complete(reserved.id(), stripe.provision(reserved));
        return repository.find(reserved.id()).orElseThrow(SubscriptionPriceNotFound::new);
    }

    @Override
    public SubscriptionPriceModel activate(UUID id) {
        return repository.activate(id);
    }
}
