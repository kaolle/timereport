package com.timereport.subscription.application.port.out;

import com.timereport.subscription.domain.StripePriceReferences;
import com.timereport.subscription.domain.SubscriptionPriceModel;

public interface SubscriptionPriceProvisioner {
    StripePriceReferences provision(SubscriptionPriceModel model);
}
