package com.timereport.invoicing.adapter.out.subscription;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.invoicing.application.port.out.InvoiceCreationAccess;
import com.timereport.invoicing.application.service.SubscriptionRequired;
import com.timereport.subscription.application.port.in.SubscriptionAccess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;

@ApplicationScoped
public class SubscriptionInvoiceCreationAccess implements InvoiceCreationAccess {
    private final AccountIdentity identity;
    private final SubscriptionAccess subscriptions;
    private final boolean enforce;
    private final Clock clock;

    @Inject
    public SubscriptionInvoiceCreationAccess(AccountIdentity identity, SubscriptionAccess subscriptions,
                                             @ConfigProperty(name = "subscription.enforce-invoice-access", defaultValue = "false")
                                             boolean enforce) {
        this(identity, subscriptions, enforce, Clock.systemUTC());
    }

    SubscriptionInvoiceCreationAccess(AccountIdentity identity, SubscriptionAccess subscriptions,
                                      boolean enforce, Clock clock) {
        this.identity = identity;
        this.subscriptions = subscriptions;
        this.enforce = enforce;
        this.clock = clock;
    }

    @Override
    public void requireAccess() {
        if (enforce && !subscriptions.status(identity.accountId()).canInvoiceAt(clock.instant()))
            throw new SubscriptionRequired();
    }
}
