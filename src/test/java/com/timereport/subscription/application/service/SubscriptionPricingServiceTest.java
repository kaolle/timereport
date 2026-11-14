package com.timereport.subscription.application.service;

import com.timereport.subscription.application.port.out.SubscriptionPriceProvisioner;
import com.timereport.subscription.application.port.out.SubscriptionPriceRepository;
import com.timereport.subscription.domain.StripePriceReferences;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubscriptionPricingServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private final UUID actor = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final SubscriptionPriceRepository repository = mock(SubscriptionPriceRepository.class);
    private final SubscriptionPriceProvisioner stripe = mock(SubscriptionPriceProvisioner.class);
    private final SubscriptionPricingService service = new SubscriptionPricingService(repository, stripe,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final StripePriceReferences references = new StripePriceReferences("prod_test", "price_test", "txr_test");

    @Test
    void reservesBeforeStripeAndPersistsTheReadyReferences() {
        var draft = draft(NOW);
        var ready = draft.toBuilder().state(SubscriptionPriceModel.State.READY).stripe(references).build();
        when(repository.reserve(any())).thenReturn(draft);
        when(stripe.provision(draft)).thenReturn(references);
        when(repository.find(id)).thenReturn(Optional.of(ready));

        assertEquals(ready, create());
        var order = inOrder(repository, stripe);
        order.verify(repository).reserve(draft);
        order.verify(stripe).provision(draft);
        order.verify(repository).complete(id, references);
        order.verify(repository).find(id);
    }

    @Test
    void completedRequestRetryDoesNotCreateAnotherStripePriceEvenAfterTheRetryWindow() {
        var ready = draft(NOW.minusSeconds(48 * 3600)).toBuilder()
                .state(SubscriptionPriceModel.State.READY).stripe(references).active(true).build();
        when(repository.reserve(any())).thenReturn(ready);

        assertEquals(ready, create());
        verifyNoInteractions(stripe);
        verify(repository, never()).complete(any(), any());
    }

    @Test
    void retriesUseTheReservedModelAndRetainTheSameIdempotencyIdentity() {
        var reserved = draft(NOW.minusSeconds(60));
        var ready = reserved.toBuilder().state(SubscriptionPriceModel.State.READY).stripe(references).build();
        when(repository.reserve(any())).thenReturn(reserved);
        when(stripe.provision(reserved)).thenThrow(new SubscriptionBillingUnavailable("temporary"))
                .thenReturn(references);
        when(repository.find(id)).thenReturn(Optional.of(ready));

        assertThrows(SubscriptionBillingUnavailable.class, this::create);
        assertEquals(ready, create());
        verify(stripe, times(2)).provision(reserved);
        verify(repository).complete(id, references);
    }

    @Test
    void staleIncompleteRequestCannotOutliveStripesIdempotencyRetention() {
        when(repository.reserve(any())).thenReturn(draft(NOW.minusSeconds(23 * 3600)));

        assertThrows(SubscriptionPurchaseConflict.class, this::create);
        verifyNoInteractions(stripe);
        verify(repository, never()).complete(any(), any());
    }

    @Test
    void conflictingReservationStopsBeforeStripe() {
        when(repository.reserve(any())).thenThrow(new SubscriptionPurchaseConflict("changed request"));

        assertThrows(SubscriptionPurchaseConflict.class, this::create);
        verifyNoInteractions(stripe);
    }

    private SubscriptionPriceModel create() {
        return service.create(actor, id, "Standard", SubscriptionPlan.MONTHLY,
                new BigDecimal("49"), new BigDecimal("25"), false);
    }

    private SubscriptionPriceModel draft(Instant at) {
        return SubscriptionPriceModel.builder().id(id).name("Standard").plan(SubscriptionPlan.MONTHLY)
                .amount(new BigDecimal("49")).vatPercent(new BigDecimal("25")).vatInclusive(false)
                .state(SubscriptionPriceModel.State.CREATING).createdBy(actor).createdAt(at).build();
    }
}
