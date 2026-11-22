package com.timereport.subscription.application.port.in;

import com.timereport.subscription.domain.MaintenanceResult;

public interface MaintainSubscriptions {
    MaintenanceResult run();
}
