package com.timereport.subscription.application.port.out;

import com.timereport.subscription.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface SubscriptionLifecycleRepository {
    Optional<UUID> accountForCustomer(String customerId);
    <T> T locked(UUID accountId, Supplier<T> work);
    boolean beginEvent(BillingEvent event);
    void completeEvent(String eventId, Instant now);
    Optional<SubscriptionAgreement> agreement(UUID accountId, String subscriptionId);
    Optional<SubscriptionCheckoutBinding> binding(UUID accountId, UUID attemptId, String sessionId);
    void saveAgreement(SubscriptionAgreement agreement);
    void recordPaidPeriod(UUID accountId, String subscriptionId, BillingInvoice invoice);
    void resolveCheckout(UUID accountId, UUID attemptId, String subscriptionId, Instant now);
    List<UUID> maintenanceCandidates(UUID afterAccountId, int limit);
    void markMaintenanceChecked(UUID accountId, Instant now);
    List<String> subscriptionIds(UUID accountId);
    Optional<String> customerId(UUID accountId);
    Optional<Instant> paidUntil(UUID accountId, String subscriptionId);
    boolean ownerAccess(UUID accountId);
    void alert(UUID accountId, String reference, String kind, String subscriptionId, Instant now);
}
