package com.timereport.subscription.application.service;

import com.timereport.subscription.application.port.out.SubscriptionAccountEmail;
import com.timereport.subscription.application.port.out.SubscriptionBillingGateway;
import com.timereport.subscription.application.port.out.SubscriptionPurchaseRepository;
import com.timereport.subscription.application.port.out.SubscriptionPriceRepository;
import com.timereport.subscription.domain.CheckoutAttempt;
import com.timereport.subscription.domain.HostedCheckout;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;
import com.timereport.subscription.domain.StripePriceReferences;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionPurchaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private final SubscriptionPurchaseRepository repository = mock(SubscriptionPurchaseRepository.class);
    private final SubscriptionBillingGateway billing = mock(SubscriptionBillingGateway.class);
    private final SubscriptionAccountEmail accounts = mock(SubscriptionAccountEmail.class);
    private final SubscriptionPriceRepository prices = mock(SubscriptionPriceRepository.class);
    private final SubscriptionPurchaseService service = new SubscriptionPurchaseService(
            repository, billing, accounts, prices, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void newCheckoutBindsCustomerPlanAndIdempotencyToAccount() {
        UUID account = UUID.randomUUID();
        var attempt = attempt(account, SubscriptionPlan.MONTHLY, CheckoutAttempt.State.CREATING);
        var session = session("cus_own", HostedCheckout.State.OPEN);
        var price = price(SubscriptionPlan.MONTHLY);
        when(prices.forCheckout(account, attempt.id(), SubscriptionPlan.MONTHLY)).thenReturn(price);
        when(repository.reserve(account, SubscriptionPlan.MONTHLY, NOW)).thenReturn(attempt);
        when(repository.customerId(account)).thenReturn(Optional.empty());
        when(repository.customerIdempotencyKey(account)).thenReturn("random-customer-key");
        when(accounts.email(account)).thenReturn("owner@example.test");
        when(billing.createCustomer(account, "owner@example.test", "random-customer-key")).thenReturn("cus_own");
        when(repository.saveCustomerId(account, "cus_own")).thenReturn("cus_own");
        when(billing.createCheckout(account, attempt.id(), "cus_own", price,
                attempt.idempotencyKey())).thenReturn(session);

        assertEquals(session, service.checkout(account, SubscriptionPlan.MONTHLY));
        verify(repository).open(attempt.id(), session.sessionId(), session.expiresAt());
        verify(billing).requireCheckoutConfigured();
        verify(prices).forCheckout(account, attempt.id(), SubscriptionPlan.MONTHLY);
    }

    @Test
    void duplicateClickReusesTheSameStripeSession() {
        UUID account = UUID.randomUUID();
        var attempt = new CheckoutAttempt(UUID.randomUUID(), account, SubscriptionPlan.YEARLY,
                CheckoutAttempt.State.OPEN, "idem", "cs_test_existing", NOW.plusSeconds(3600));
        var session = session("cus_own", HostedCheckout.State.OPEN);
        when(repository.reserve(account, SubscriptionPlan.YEARLY, NOW)).thenReturn(attempt);
        when(repository.customerId(account)).thenReturn(Optional.of("cus_own"));
        when(billing.retrieveCheckout("cs_test_existing")).thenReturn(session);

        assertEquals(session, service.checkout(account, SubscriptionPlan.YEARLY));
        verify(billing, never()).createCheckout(any(), any(), anyString(), any(), anyString());
        verifyNoInteractions(prices);
    }

    @Test
    void anotherPlanCannotReplaceACreatingCheckout() {
        UUID account = UUID.randomUUID();
        when(repository.reserve(account, SubscriptionPlan.YEARLY, NOW)).thenReturn(
                attempt(account, SubscriptionPlan.MONTHLY, CheckoutAttempt.State.CREATING));

        assertThrows(SubscriptionPurchaseConflict.class, () -> service.checkout(account, SubscriptionPlan.YEARLY));
        verify(billing, never()).createCustomer(any(), anyString(), anyString());
    }

    @Test
    void anotherPlanCannotReplaceACheckoutStillOpenInStripe() {
        UUID account = UUID.randomUUID();
        var attempt = new CheckoutAttempt(UUID.randomUUID(), account, SubscriptionPlan.MONTHLY,
                CheckoutAttempt.State.OPEN, "idem", "cs_test_existing", NOW.plusSeconds(3600));
        when(repository.reserve(account, SubscriptionPlan.YEARLY, NOW)).thenReturn(attempt);
        when(repository.customerId(account)).thenReturn(Optional.of("cus_own"));
        when(billing.retrieveCheckout("cs_test_existing")).thenReturn(session("cus_own", HostedCheckout.State.OPEN));

        assertThrows(SubscriptionPurchaseConflict.class, () -> service.checkout(account, SubscriptionPlan.YEARLY));
        verify(repository, never()).expire(any());
        verify(billing, never()).createCheckout(any(), any(), anyString(), any(), anyString());
    }

    @Test
    void expiredMonthlyCheckoutAllowsANewYearlyCheckout() {
        UUID account = UUID.randomUUID();
        var expired = new CheckoutAttempt(UUID.randomUUID(), account, SubscriptionPlan.MONTHLY,
                CheckoutAttempt.State.OPEN, "old-idem", "cs_test_expired", NOW.minusSeconds(1));
        var yearly = attempt(account, SubscriptionPlan.YEARLY, CheckoutAttempt.State.CREATING);
        var price = price(SubscriptionPlan.YEARLY);
        when(prices.forCheckout(account, yearly.id(), SubscriptionPlan.YEARLY)).thenReturn(price);
        var session = session("cus_own", HostedCheckout.State.OPEN);
        when(repository.reserve(account, SubscriptionPlan.YEARLY, NOW)).thenReturn(expired, yearly);
        when(repository.customerId(account)).thenReturn(Optional.of("cus_own"));
        when(billing.retrieveCheckout("cs_test_expired")).thenReturn(session("cus_own", HostedCheckout.State.EXPIRED));
        when(billing.createCheckout(account, yearly.id(), "cus_own", price,
                yearly.idempotencyKey())).thenReturn(session);

        assertEquals(session, service.checkout(account, SubscriptionPlan.YEARLY));
        verify(repository).expire(expired.id());
        verify(repository).open(yearly.id(), session.sessionId(), session.expiresAt());
        verify(billing, never()).createCustomer(any(), anyString(), anyString());
    }

    @Test
    void completedCheckoutBlocksAnotherPlanUntilVerifiedPaymentEvent() {
        UUID account = UUID.randomUUID();
        var attempt = new CheckoutAttempt(UUID.randomUUID(), account, SubscriptionPlan.MONTHLY,
                CheckoutAttempt.State.OPEN, "idem", "cs_test_existing", NOW.plusSeconds(3600));
        when(repository.reserve(account, SubscriptionPlan.YEARLY, NOW)).thenReturn(attempt);
        when(repository.customerId(account)).thenReturn(Optional.of("cus_own"));
        when(billing.retrieveCheckout("cs_test_existing")).thenReturn(session("cus_own", HostedCheckout.State.COMPLETE));

        assertThrows(SubscriptionPurchaseConflict.class, () -> service.checkout(account, SubscriptionPlan.YEARLY));
        verify(repository).complete(attempt.id());
        verify(repository, never()).expire(any());
        verify(billing, never()).createCheckout(any(), any(), anyString(), any(), anyString());
    }

    @Test
    void expiredCheckoutForAnotherCustomerCannotBeReplaced() {
        UUID account = UUID.randomUUID();
        var attempt = new CheckoutAttempt(UUID.randomUUID(), account, SubscriptionPlan.MONTHLY,
                CheckoutAttempt.State.OPEN, "idem", "cs_test_existing", NOW.minusSeconds(1));
        when(repository.reserve(account, SubscriptionPlan.YEARLY, NOW)).thenReturn(attempt);
        when(repository.customerId(account)).thenReturn(Optional.of("cus_own"));
        when(billing.retrieveCheckout("cs_test_existing")).thenReturn(session("cus_other", HostedCheckout.State.EXPIRED));

        assertThrows(SubscriptionBillingUnavailable.class, () -> service.checkout(account, SubscriptionPlan.YEARLY));
        verify(repository, never()).expire(any());
        verify(repository, never()).complete(any());
        verify(billing, never()).createCheckout(any(), any(), anyString(), any(), anyString());
    }

    @Test
    void completedCheckoutWaitsForVerifiedPaymentEvent() {
        UUID account = UUID.randomUUID();
        var attempt = new CheckoutAttempt(UUID.randomUUID(), account, SubscriptionPlan.MONTHLY,
                CheckoutAttempt.State.OPEN, "idem", "cs_test_existing", NOW.plusSeconds(3600));
        when(repository.reserve(account, SubscriptionPlan.MONTHLY, NOW)).thenReturn(attempt);
        when(repository.customerId(account)).thenReturn(Optional.of("cus_own"));
        when(billing.retrieveCheckout("cs_test_existing")).thenReturn(session("cus_own", HostedCheckout.State.COMPLETE));

        assertThrows(SubscriptionPurchaseConflict.class, () -> service.checkout(account, SubscriptionPlan.MONTHLY));
        verify(repository).complete(attempt.id());
        verify(billing, never()).createCheckout(any(), any(), anyString(), any(), anyString());
    }

    @Test
    void portalAlwaysUsesTheAuthenticatedAccountsStoredCustomer() {
        UUID account = UUID.randomUUID();
        when(repository.customerId(account)).thenReturn(Optional.of("cus_own"));
        when(billing.createPortal("cus_own")).thenReturn("https://billing.stripe.test/portal");

        assertEquals("https://billing.stripe.test/portal", service.portal(account));
        verify(billing).requirePortalConfigured();
        verify(billing).createPortal("cus_own");
    }

    @Test
    void missingCatalogPriceDoesNotCreateAStripeCustomerOrCheckout() {
        UUID account = UUID.randomUUID();
        var attempt = attempt(account, SubscriptionPlan.MONTHLY, CheckoutAttempt.State.CREATING);
        when(repository.reserve(account, SubscriptionPlan.MONTHLY, NOW)).thenReturn(attempt);
        when(prices.forCheckout(account, attempt.id(), SubscriptionPlan.MONTHLY))
                .thenThrow(new SubscriptionBillingUnavailable("Inget månadspris har aktiverats."));

        assertThrows(SubscriptionBillingUnavailable.class, () -> service.checkout(account, SubscriptionPlan.MONTHLY));
        verify(billing, never()).createCustomer(any(), anyString(), anyString());
        verify(billing, never()).createCheckout(any(), any(), anyString(), any(), anyString());
    }

    private SubscriptionPriceModel price(SubscriptionPlan plan) {
        return SubscriptionPriceModel.builder().id(UUID.randomUUID()).name("Fakturering").plan(plan)
                .amount(new BigDecimal("49.00")).vatPercent(new BigDecimal("25"))
                .state(SubscriptionPriceModel.State.READY)
                .stripe(new StripePriceReferences("prod_test", "price_test", "txr_test"))
                .createdBy(UUID.randomUUID()).createdAt(NOW).build();
    }

    private CheckoutAttempt attempt(UUID account, SubscriptionPlan plan, CheckoutAttempt.State state) {
        return new CheckoutAttempt(UUID.randomUUID(), account, plan, state, "idem", null, null);
    }

    private HostedCheckout session(String customer, HostedCheckout.State state) {
        return new HostedCheckout("cs_test_existing", customer,
                state == HostedCheckout.State.OPEN ? "https://checkout.stripe.test/session" : null,
                NOW.plusSeconds(3600), state);
    }
}
