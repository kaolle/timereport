package com.timereport.timeentry.application.port.out;

import com.timereport.timeentry.domain.CustomerCode;
import com.timereport.timeentry.domain.RegisteredCustomerCode;
import java.util.List;
import java.util.Optional;

public interface CustomerCodeRepository {
    Optional<RegisteredCustomerCode> find(CustomerCode code);
    List<RegisteredCustomerCode> list();
    /** Must be called inside WriteTransaction. Zero means insert. */
    void save(RegisteredCustomerCode code, long expectedVersion);
}
