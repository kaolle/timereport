package com.timereport.subscription.application.service;

import com.timereport.subscription.application.port.in.SubscriptionRenewalReminders;
import com.timereport.subscription.application.port.out.*;
import com.timereport.subscription.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionLifecycleServiceTest {
    private static final UUID ACCOUNT = UUID.randomUUID(), ATTEMPT = UUID.randomUUID(), MODEL = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant NEXT_END = Instant.parse("2027-01-01T00:00:00Z");
    private SubscriptionLifecycleRepository repository;
    private SubscriptionLifecycleGateway billing;
    private SubscriptionPriceRepository prices;
    private SubscriptionRenewalReminders reminders;
    private SubscriptionLifecycleService service;
    private SubscriptionPriceModel model;
    private final MutableClock clock = new MutableClock();
    private final Map<String, SubscriptionAgreement> agreements = new LinkedHashMap<>();
    private final Map<String, BillingInvoice> periods = new HashMap<>();
    private final Set<String> events = new HashSet<>();
    private final AtomicReference<BillingSubscription> remote = new AtomicReference<>();
    private int sequence;

    @BeforeEach
    void setup() {
        repository = mock(SubscriptionLifecycleRepository.class);
        billing = mock(SubscriptionLifecycleGateway.class);
        prices = mock(SubscriptionPriceRepository.class);
        reminders = mock(SubscriptionRenewalReminders.class);
        model = SubscriptionPriceModel.builder().id(MODEL).name("Månad").plan(SubscriptionPlan.MONTHLY)
                .amount(new BigDecimal("49.00")).vatPercent(new BigDecimal("25.00")).vatInclusive(false)
                .state(SubscriptionPriceModel.State.READY)
                .stripe(new StripePriceReferences("prod_test", "price_test", "txr_test"))
                .active(true).createdBy(ACCOUNT).createdAt(START).build();
        clock.now = START.plus(Duration.ofDays(3));
        when(repository.accountForCustomer("cus_test")).thenReturn(Optional.of(ACCOUNT));
        when(repository.locked(eq(ACCOUNT), any())).thenAnswer(invocation ->
                invocation.<Supplier<Object>>getArgument(1).get());
        when(repository.beginEvent(any())).thenAnswer(invocation ->
                events.add(invocation.<BillingEvent>getArgument(0).id()));
        when(repository.agreement(eq(ACCOUNT), anyString())).thenAnswer(invocation ->
                Optional.ofNullable(agreements.get(invocation.<String>getArgument(1))));
        when(repository.binding(ACCOUNT, ATTEMPT, "cs_test"))
                .thenReturn(Optional.of(new SubscriptionCheckoutBinding(ACCOUNT, ATTEMPT, "cs_test", MODEL, "price_test", true)));
        when(repository.customerId(ACCOUNT)).thenReturn(Optional.of("cus_test"));
        when(repository.subscriptionIds(ACCOUNT)).thenAnswer(invocation -> List.copyOf(agreements.keySet()));
        when(repository.paidUntil(eq(ACCOUNT), anyString())).thenAnswer(invocation -> periods.values().stream()
                .filter(p -> p.subscriptionId().equals(invocation.getArgument(1)))
                .filter(p -> !p.periodStart().isAfter(clock.instant())).map(BillingInvoice::periodEnd).max(Instant::compareTo));
        doAnswer(invocation -> {
            var agreement = invocation.<SubscriptionAgreement>getArgument(0);
            agreements.put(agreement.subscriptionId(), agreement);
            return null;
        }).when(repository).saveAgreement(any());
        doAnswer(invocation -> {
            var invoice = invocation.<BillingInvoice>getArgument(2);
            assertTrue(agreements.containsKey(invoice.subscriptionId()), "Agreement must exist before its paid period");
            periods.putIfAbsent(invoice.id(), invoice);
            return null;
        }).when(repository).recordPaidPeriod(eq(ACCOUNT), anyString(), any());
        when(prices.find(MODEL)).thenAnswer(invocation -> Optional.of(model));
        when(billing.locate(any())).thenReturn(new BillingEventLink("cus_test", "sub_test", null));
        when(billing.fetch("sub_test")).thenAnswer(invocation -> remote.get());
        when(billing.subscriptionIds("cus_test")).thenReturn(List.of("sub_test"));
        when(repository.maintenanceCandidates(any(), eq(50))).thenAnswer(invocation ->
                invocation.getArgument(0) == null ? List.of(ACCOUNT) : List.of());
        var accounts = mock(SubscriptionAccountEmail.class);
        when(accounts.email(ACCOUNT)).thenReturn("buyer@example.test");
        service = new SubscriptionLifecycleService(repository, billing, prices, accounts, reminders,
                clock, Duration.ofDays(7));
        remote.set(snapshot(BillingSubscription.State.ACTIVE, false, List.of(initialPayment())));
    }

    @Test
    void firstPaidCheckoutCreatesAgreementAndExactlyOnePeriod() {
        assertEquals(BillingEventResult.PROCESSED, service.process(event("invoice.paid")));
        assertEquals(SubscriptionState.ACTIVE, agreement().state());
        assertEquals(1, periods.size());
        verify(repository).resolveCheckout(ACCOUNT, ATTEMPT, "sub_test", clock.instant());
        verify(reminders).sync(argThat(c -> c.renewing() && c.amountIncludingVat().equals(new BigDecimal("61.25"))), any());
    }

    @Test
    void duplicateEventDoesNotReapplyStateOrPaidPeriod() {
        var event = event("invoice.paid");
        service.process(event);
        assertEquals(BillingEventResult.DUPLICATE, service.process(event));
        verify(billing, times(1)).fetch("sub_test");
        assertEquals(1, periods.size());
    }

    @Test
    void checkoutCompletionWithoutPaidInvoiceDoesNotOpenAccess() {
        remote.set(snapshot(BillingSubscription.State.ACTIVE, false, List.of()));
        service.process(event("checkout.session.completed"));
        assertEquals(SubscriptionState.PENDING, agreement().state());
        assertTrue(periods.isEmpty());
        verify(reminders).sync(argThat(c -> !c.renewing()), any());
    }

    @Test
    void failedFirstPaymentNeverGetsGrace() {
        remote.set(snapshot(BillingSubscription.State.INCOMPLETE, false, List.of()));
        service.process(event("invoice.payment_failed"));
        assertEquals(SubscriptionState.PENDING, agreement().state());
        assertNull(agreement().graceUntil());
        verify(billing, never()).terminate(anyString(), anyString());
    }

    @Test
    void failedRenewalHasFixedSevenDayDeadlineDespiteRetries() {
        service.process(event("invoice.paid"));
        clock.now = END.plus(Duration.ofDays(2));
        remote.set(snapshot(BillingSubscription.State.PAST_DUE, false, List.of(initialPayment())));
        service.process(event("invoice.payment_failed"));
        assertEquals(SubscriptionState.PAST_DUE, agreement().state());
        assertEquals(END.plus(Duration.ofDays(7)), agreement().graceUntil());
        clock.now = END.plus(Duration.ofDays(5));
        service.process(event("invoice.payment_action_required"));
        assertEquals(END.plus(Duration.ofDays(7)), agreement().graceUntil());
    }

    @Test
    void onTimePaymentDeliveredAfterGraceStillRecoversSubscription() {
        failRenewal();
        clock.now = END.plus(Duration.ofDays(9));
        remote.set(snapshot(BillingSubscription.State.ACTIVE, false,
                List.of(initialPayment(), renewalPayment(END.plus(Duration.ofDays(3))))));
        service.process(event("invoice.paid"));
        assertEquals(SubscriptionState.ACTIVE, agreement().state());
        assertNull(agreement().graceUntil());
        assertEquals(2, periods.size());
        verify(billing, never()).terminate(anyString(), anyString());
    }

    @Test
    void expiredGraceStopsRemoteRetriesAndRetainsHistoricalPaidPeriod() {
        failRenewal();
        clock.now = END.plus(Duration.ofDays(7));
        var result = service.run();
        assertEquals(1, result.terminated());
        verify(billing).terminate("sub_test", "timereport-lapse-sub_test");
        assertEquals("GRACE_EXPIRED", agreement().terminalReason());
        assertEquals(1, periods.size());
    }

    @Test
    void paymentAtOrAfterDeadlineDoesNotRestoreOldAgreement() {
        failRenewal();
        clock.now = END.plus(Duration.ofDays(7));
        remote.set(snapshot(BillingSubscription.State.ACTIVE, false,
                List.of(initialPayment(), renewalPayment(clock.instant()))));
        service.process(event("invoice.paid"));
        assertEquals("LATE_RENEWAL_PAYMENT", agreement().terminalReason());
        assertEquals(1, periods.size());
        verify(repository).alert(eq(ACCOUNT), eq("in_renewal"), eq("LATE_RENEWAL_PAYMENT"), eq("sub_test"), any());
    }

    @Test
    void lateEventsCannotResurrectAnAlreadyTerminatedAgreement() {
        failRenewal();
        clock.now = END.plus(Duration.ofDays(8));
        service.run();
        var tombstone = agreement();
        remote.set(snapshot(BillingSubscription.State.ACTIVE, false,
                List.of(initialPayment(), renewalPayment(clock.instant().plusSeconds(1)))));
        service.process(event("invoice.paid"));
        assertEquals(tombstone, agreement());
        assertEquals(1, periods.size());
        verify(repository).alert(eq(ACCOUNT), eq("in_renewal"), eq("PAYMENT_AFTER_TERMINATION"), eq("sub_test"), any());
    }

    @Test
    void scheduledCancellationStopsRemindersButRetainsPaidAccess() {
        remote.set(snapshot(BillingSubscription.State.ACTIVE, true, List.of(initialPayment())));
        service.process(event("customer.subscription.updated"));
        assertTrue(agreement().cancelAtPeriodEnd());
        assertEquals(SubscriptionState.ACTIVE, agreement().state());
        assertEquals(END, periods.get("in_initial").periodEnd());
        verify(reminders).sync(argThat(c -> !c.renewing()), any());
    }

    @Test
    void canceledEventCanRecoverMissingHistoricalPaymentButNeverNewPeriodsLater() {
        remote.set(snapshot(BillingSubscription.State.CANCELED, true, List.of(initialPayment())));
        service.process(event("customer.subscription.deleted"));
        assertEquals("CANCELED", agreement().terminalReason());
        assertEquals(1, periods.size());
    }

    @Test
    void firstReconciliationOfCanceledAgreementRejectsPaymentCollectedAfterCancellation() {
        clock.now = START.plusSeconds(30);
        var late = new BillingInvoice("in_late", "sub_test", "cus_test", "price_test", BillingInvoice.State.PAID,
                START, END, 6125, 6125, START.plusSeconds(60), false);
        remote.set(snapshot(BillingSubscription.State.CANCELED, true, List.of(late)));
        clock.now = START.plus(Duration.ofDays(3));
        service.process(event("invoice.paid"));
        assertTrue(periods.isEmpty());
        assertEquals("CANCELED", agreement().terminalReason());
        verify(repository).alert(eq(ACCOUNT), eq("in_late"), eq("PAYMENT_AFTER_TERMINATION"), eq("sub_test"), any());
    }

    @Test
    void yearlyAndVatInclusivePricingUseVerifiedOriginalModel() {
        model = model.toBuilder().plan(SubscriptionPlan.YEARLY).name("År").vatInclusive(true)
                .amount(new BigDecimal("490.00")).build();
        remote.set(snapshot(BillingSubscription.State.ACTIVE, false, List.of(initialPayment())));
        service.process(event("invoice.paid"));
        assertEquals(SubscriptionPlan.YEARLY, agreement().plan());
        verify(reminders).sync(argThat(c -> c.plan() == SubscriptionPlan.YEARLY
                && c.amountIncludingVat().equals(new BigDecimal("490.00"))), any());
    }

    @Test
    void accountOrPriceBindingMismatchIsAuditedWithoutGrantingAnything() {
        when(repository.binding(ACCOUNT, ATTEMPT, "cs_test")).thenReturn(Optional.empty());
        assertEquals(BillingEventResult.IGNORED, service.process(event("invoice.paid")));
        assertTrue(agreements.isEmpty());
        assertTrue(periods.isEmpty());
        verify(repository).alert(eq(ACCOUNT), eq("sub_test"), eq("UNMANAGED_SUBSCRIPTION"), eq("sub_test"), any());
    }

    @Test
    void refundAndDisputeRequireReviewWithoutGrantingOrExtendingPeriods() {
        service.process(event("invoice.paid"));
        var before = agreement();
        when(billing.locate(any())).thenReturn(new BillingEventLink("cus_test", null, "REFUND_REVIEW"));
        service.process(event("charge.refunded"));
        assertEquals(before, agreement());
        assertEquals(1, periods.size());
        verify(repository).alert(eq(ACCOUNT), anyString(), eq("REFUND_REVIEW"), isNull(), any());
    }

    @Test
    void unknownStripeCustomerIsIgnoredWithoutAnyAccountWrites() {
        when(repository.accountForCustomer("cus_test")).thenReturn(Optional.empty());
        assertEquals(BillingEventResult.IGNORED, service.process(event("invoice.paid")));
        verify(repository, never()).locked(any(), any());
    }

    @Test
    void transientStripeFailureNeverMarksWebhookAsProcessed() {
        when(billing.fetch("sub_test")).thenThrow(new SubscriptionBillingUnavailable("Temporary"));
        assertThrows(SubscriptionBillingUnavailable.class, () -> service.process(event("invoice.paid")));
        verify(repository, never()).completeEvent(anyString(), any());
    }

    @Test
    void maintenanceFailureIsReportedForSchedulerRetry() {
        when(billing.subscriptionIds("cus_test")).thenThrow(new SubscriptionBillingUnavailable("Temporary"));
        var result = service.run();
        assertEquals(1, result.checked());
        assertEquals(1, result.failed());
        assertEquals(0, result.remindersQueued());
        verify(repository).markMaintenanceChecked(ACCOUNT, clock.instant());
    }

    private void failRenewal() {
        service.process(event("invoice.paid"));
        clock.now = END.plus(Duration.ofDays(2));
        remote.set(snapshot(BillingSubscription.State.PAST_DUE, false, List.of(initialPayment())));
        service.process(event("invoice.payment_failed"));
    }

    private BillingEvent event(String type) {
        return new BillingEvent("evt_" + ++sequence, type, "in_initial", START);
    }

    private SubscriptionAgreement agreement() { return agreements.get("sub_test"); }

    private BillingSubscription snapshot(BillingSubscription.State state, boolean cancel, List<BillingInvoice> invoices) {
        return BillingSubscription.builder().id("sub_test").customerId("cus_test").accountId(ACCOUNT).priceModelId(MODEL)
                .checkoutAttemptId(ATTEMPT).checkoutSessionId("cs_test").priceId("price_test").plan(model.plan())
                .state(state).periodStart(START).periodEnd(END).cancelAtPeriodEnd(cancel)
                .endedAt(state == BillingSubscription.State.CANCELED ? clock.instant() : null).invoices(invoices).build();
    }

    private BillingInvoice initialPayment() {
        return new BillingInvoice("in_initial", "sub_test", "cus_test", "price_test", BillingInvoice.State.PAID,
                START, END, 6125, 6125, START, false);
    }

    private BillingInvoice renewalPayment(Instant paidAt) {
        return new BillingInvoice("in_renewal", "sub_test", "cus_test", "price_test", BillingInvoice.State.PAID,
                END, NEXT_END, 6125, 6125, paidAt, true);
    }

    private static class MutableClock extends Clock {
        private Instant now;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
