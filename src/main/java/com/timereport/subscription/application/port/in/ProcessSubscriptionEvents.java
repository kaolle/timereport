package com.timereport.subscription.application.port.in;

import com.timereport.subscription.domain.BillingEvent;
import com.timereport.subscription.domain.BillingEventResult;

public interface ProcessSubscriptionEvents {
    BillingEventResult process(BillingEvent event);
}
