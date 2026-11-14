package com.timereport.authentication.application.port.out;

import java.util.Set;
import java.util.UUID;

public interface AccountRoles {
    /** Returns effective roles, including the implicit user role. */
    Set<String> roles(UUID accountId);
}
