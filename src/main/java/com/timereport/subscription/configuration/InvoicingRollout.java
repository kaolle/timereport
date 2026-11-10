package com.timereport.subscription.configuration;

import com.timereport.subscription.domain.SubscriptionSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class InvoicingRollout {
    private final boolean ownerOnly;

    public InvoicingRollout(@ConfigProperty(name = "subscription.invoicing-owner-only") boolean ownerOnly) {
        this.ownerOnly = ownerOnly;
    }

    public boolean availableTo(SubscriptionSnapshot subscription) {
        return !ownerOnly || subscription.ownerAccess();
    }
}
