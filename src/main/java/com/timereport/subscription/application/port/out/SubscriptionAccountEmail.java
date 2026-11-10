package com.timereport.subscription.application.port.out;

import java.util.UUID;

public interface SubscriptionAccountEmail {
    String email(UUID accountId);
}
