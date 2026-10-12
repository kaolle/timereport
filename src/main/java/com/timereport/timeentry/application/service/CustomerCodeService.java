package com.timereport.timeentry.application.service;

import com.timereport.timeentry.application.port.in.CustomerCodes;
import com.timereport.timeentry.application.port.out.CustomerCodeRepository;
import com.timereport.timeentry.application.port.out.WriteTransaction;
import com.timereport.timeentry.domain.CustomerCode;
import com.timereport.timeentry.domain.RegisteredCustomerCode;
import java.util.List;
import java.util.Objects;

public class CustomerCodeService implements CustomerCodes {
    private final CustomerCodeRepository codes;
    private final WriteTransaction transaction;

    public CustomerCodeService(CustomerCodeRepository codes, WriteTransaction transaction) {
        this.codes = codes;
        this.transaction = transaction;
    }

    @Override
    public RegisteredCustomerCode save(CustomerCode code, boolean active, long expectedVersion) {
        Objects.requireNonNull(code, "Customer code is required");
        Versions.validate(expectedVersion);
        return transaction.execute(() -> {
            var current = codes.find(code).orElse(null);
            if (current != null && current.version() == expectedVersion + 1 && current.active() == active) {
                return current;
            }
            if (current == null && expectedVersion != 0 || current != null
                    && (current.version() != expectedVersion || expectedVersion == 0)) {
                throw new ConflictException("Customer code changed; reload before saving");
            }
            var saved = RegisteredCustomerCode.builder().code(code).active(active).version(expectedVersion + 1).build();
            codes.save(saved, expectedVersion);
            return saved;
        });
    }

    @Override
    public List<RegisteredCustomerCode> list() {
        return List.copyOf(codes.list());
    }
}
