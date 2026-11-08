package com.timereport.subscription.application.service;

import com.timereport.subscription.application.port.in.SubscriptionAccess;
import com.timereport.subscription.application.port.out.SubscriptionRepository;
import com.timereport.subscription.domain.SubscriptionSnapshot;

import java.util.UUID;

public class SubscriptionAccessService implements SubscriptionAccess {
    private final SubscriptionRepository repository;

    public SubscriptionAccessService(SubscriptionRepository repository) {
        this.repository = repository;
    }

    @Override
    public SubscriptionSnapshot status(UUID accountId) {
        return repository.status(accountId);
    }
}
