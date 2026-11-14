package com.timereport.subscription.application.port.out;

import com.timereport.subscription.domain.HostedCheckout;
import com.timereport.subscription.domain.SubscriptionPriceModel;

import java.util.UUID;

public interface SubscriptionBillingGateway {
    void requireCheckoutConfigured();

    void requirePortalConfigured();

    String createCustomer(UUID accountId, String email, String idempotencyKey);

    HostedCheckout createCheckout(UUID accountId, UUID attemptId, String customerId,
                                  SubscriptionPriceModel model, String idempotencyKey);

    HostedCheckout retrieveCheckout(String sessionId);

    String createPortal(String customerId);
}
