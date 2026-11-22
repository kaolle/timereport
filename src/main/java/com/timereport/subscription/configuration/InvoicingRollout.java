package com.timereport.subscription.configuration;

import com.timereport.subscription.domain.SubscriptionSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class InvoicingRollout {
    private final boolean ownerOnly;
    private final boolean checkoutEnabled;
    private final boolean enforce;
    private final Set<UUID> sandboxBuyerAccountIds;

    @Inject
    public InvoicingRollout(@ConfigProperty(name = "subscription.invoicing-owner-only") boolean ownerOnly,
                           @ConfigProperty(name = "subscription.checkout-enabled") boolean checkoutEnabled,
                           @ConfigProperty(name = "subscription.sandbox-buyer-account-ids")
                           Optional<String> sandboxBuyerAccountIds,
                           @ConfigProperty(name = "subscription.enforce-invoice-access") boolean enforce) {
        this.ownerOnly = ownerOnly;
        this.checkoutEnabled = checkoutEnabled;
        this.enforce = enforce;
        this.sandboxBuyerAccountIds = Arrays.stream(sandboxBuyerAccountIds.orElse("").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    public InvoicingRollout(boolean ownerOnly, boolean checkoutEnabled, Optional<String> sandboxBuyerAccountIds) {
        this(ownerOnly, checkoutEnabled, sandboxBuyerAccountIds, true);
    }

    public boolean availableTo(SubscriptionSnapshot subscription) {
        return blockReason(subscription) == null;
    }

    public String blockReason(SubscriptionSnapshot subscription) {
        if (subscription.ownerAccess()) return null;
        if (ownerOnly) return "INVOICING_COMING_SOON";
        return enforce && !subscription.canInvoiceAt(Instant.now()) ? "SUBSCRIPTION_REQUIRED" : null;
    }

    public boolean canStartSandboxCheckout(SubscriptionSnapshot subscription) {
        return checkoutEnabled && canAccessSandboxBilling(subscription);
    }

    public boolean canAccessSandboxBilling(SubscriptionSnapshot subscription) {
        // Buying a subscription must remain possible before the account is entitled.
        return !ownerOnly || subscription.ownerAccess() || sandboxBuyerAccountIds.contains(subscription.accountId());
    }
}
