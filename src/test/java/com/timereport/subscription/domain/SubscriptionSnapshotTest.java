package com.timereport.subscription.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionSnapshotTest {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    private SubscriptionSnapshot snapshot(SubscriptionState state, Instant paid, Instant grace, boolean owner) {
        return SubscriptionSnapshot.builder().accountId(UUID.randomUUID()).state(state).paidUntil(paid)
                .graceUntil(grace).ownerAccess(owner).build();
    }

    @Test
    void paidAccessEndsAtTheExactPeriodBoundary() {
        var subscription = snapshot(SubscriptionState.CANCELED, NOW, null, false);
        assertTrue(subscription.canInvoiceAt(NOW.minusNanos(1)));
        assertFalse(subscription.canInvoiceAt(NOW));
    }

    @Test
    void graceOnlyAppliesToPastDueSubscriptions() {
        assertTrue(snapshot(SubscriptionState.PAST_DUE, NOW.minusSeconds(1), NOW.plusSeconds(60), false)
                .canInvoiceAt(NOW));
        assertFalse(snapshot(SubscriptionState.CANCELED, NOW.minusSeconds(1), NOW.plusSeconds(60), false)
                .canInvoiceAt(NOW));
        assertFalse(snapshot(SubscriptionState.PAST_DUE, null, NOW.plusSeconds(60), false)
                .canInvoiceAt(NOW));
    }

    @Test
    void ownerGrantIsIndependentOfPayment() {
        assertTrue(snapshot(SubscriptionState.NONE, null, null, true).canInvoiceAt(NOW));
        assertFalse(snapshot(SubscriptionState.NONE, null, null, false).canInvoiceAt(NOW));
    }
}
