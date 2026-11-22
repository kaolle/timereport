package com.timereport.subscription.application.port.out;

import com.timereport.subscription.domain.BillingEvent;
import com.timereport.subscription.domain.BillingEventLink;
import com.timereport.subscription.domain.BillingSubscription;
import java.util.List;

public interface SubscriptionLifecycleGateway {
    BillingEventLink locate(BillingEvent event);
    BillingSubscription fetch(String subscriptionId);
    List<String> subscriptionIds(String customerId);
    void terminate(String subscriptionId, String idempotencyKey);
}
