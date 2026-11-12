package com.timereport.subscription.configuration;

import com.timereport.subscription.domain.SubscriptionSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class InvoicingRollout {
    private final boolean ownerOnly;
    private final boolean checkoutEnabled;
    private final Set<UUID> sandboxBuyerAccountIds;

    public InvoicingRollout(@ConfigProperty(name = "subscription.invoicing-owner-only") boolean ownerOnly,
                           @ConfigProperty(name = "subscription.checkout-enabled") boolean checkoutEnabled,
                           @ConfigProperty(name = "subscription.sandbox-buyer-account-ids")
                           Optional<String> sandboxBuyerAccountIds) {
        this.ownerOnly = ownerOnly;
        this.checkoutEnabled = checkoutEnabled;
        this.sandboxBuyerAccountIds = Arrays.stream(sandboxBuyerAccountIds.orElse("").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean availableTo(SubscriptionSnapshot subscription) {
        return !ownerOnly || subscription.ownerAccess();
    }

    public boolean canStartSandboxCheckout(SubscriptionSnapshot subscription) {
        return checkoutEnabled && canAccessSandboxBilling(subscription);
    }

    public boolean canAccessSandboxBilling(SubscriptionSnapshot subscription) {
        return availableTo(subscription) || sandboxBuyerAccountIds.contains(subscription.accountId());
    }
}
