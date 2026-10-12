package com.timereport.timeentry.application.port.in;

import com.timereport.timeentry.domain.CustomerCode;
import com.timereport.timeentry.domain.RegisteredCustomerCode;
import java.util.List;

public interface CustomerCodes {
    RegisteredCustomerCode save(CustomerCode code, boolean active, long expectedVersion);
    List<RegisteredCustomerCode> list();
}
