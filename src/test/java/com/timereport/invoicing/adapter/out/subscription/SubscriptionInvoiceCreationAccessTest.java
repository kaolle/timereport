package com.timereport.invoicing.adapter.out.subscription;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.invoicing.application.service.SubscriptionRequired;
import com.timereport.subscription.application.port.in.SubscriptionAccess;
import com.timereport.subscription.domain.SubscriptionSnapshot;
import com.timereport.subscription.domain.SubscriptionState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SubscriptionInvoiceCreationAccessTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void enforcedGateUsesOnlyTheAuthenticatedAccount() {
        var identity = mock(AccountIdentity.class);
        var subscriptions = mock(SubscriptionAccess.class);
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(identity.accountId()).thenReturn(other);
        when(subscriptions.status(owner)).thenReturn(snapshot(owner, true));
        when(subscriptions.status(other)).thenReturn(snapshot(other, false));
        var access = new SubscriptionInvoiceCreationAccess(identity, subscriptions, true, CLOCK);

        assertThrows(SubscriptionRequired.class, access::requireAccess);
        verify(subscriptions, never()).status(owner);
        when(identity.accountId()).thenReturn(owner);
        assertDoesNotThrow(access::requireAccess);
    }

    @Test
    void rolloutGateLeavesExistingInvoicingAvailable() {
        var identity = mock(AccountIdentity.class);
        var subscriptions = mock(SubscriptionAccess.class);
        var access = new SubscriptionInvoiceCreationAccess(identity, subscriptions, false, CLOCK);
        assertDoesNotThrow(access::requireAccess);
        verifyNoInteractions(identity, subscriptions);
    }

    private SubscriptionSnapshot snapshot(UUID id, boolean owner) {
        return SubscriptionSnapshot.builder().accountId(id).state(SubscriptionState.NONE).ownerAccess(owner).build();
    }
}
