package com.timereport.authentication.application.service;

import com.timereport.authentication.application.port.in.AccountIdentity;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountAuthorizationServiceTest {
    @Test
    void currentDatabaseRoleControlsAccessAndRevocationIsImmediate() {
        UUID accountId = UUID.randomUUID();
        var effectiveRoles = new AtomicReference<>(Set.of("user"));
        var identity = new AccountIdentity() {
            @Override
            public UUID accountId() {
                return accountId;
            }

            @Override
            public UUID sessionId() {
                return UUID.randomUUID();
            }
        };
        var authorization = new AccountAuthorizationService(identity, id -> {
            assertEquals(accountId, id);
            return effectiveRoles.get();
        });

        assertThrows(AdministrationForbidden.class, authorization::requireAdmin);
        effectiveRoles.set(Set.of("user", "admin"));
        assertDoesNotThrow(authorization::requireAdmin);
        effectiveRoles.set(Set.of("user"));
        assertThrows(AdministrationForbidden.class, authorization::requireAdmin);
    }
}
