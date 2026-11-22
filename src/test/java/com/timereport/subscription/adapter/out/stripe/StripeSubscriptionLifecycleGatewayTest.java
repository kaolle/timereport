package com.timereport.subscription.adapter.out.stripe;

import com.stripe.StripeClient;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoiceFinalizeInvoiceParams;
import com.stripe.param.InvoiceListParams;
import com.stripe.param.InvoiceUpdateParams;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.checkout.SessionListParams;
import com.stripe.service.*;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.domain.BillingEvent;
import com.timereport.subscription.domain.BillingInvoice;
import com.timereport.subscription.domain.BillingSubscription;
import com.timereport.subscription.domain.SubscriptionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StripeSubscriptionLifecycleGatewayTest {
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID MODEL = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-11-21T12:00:00Z");
    private static final Instant END = Instant.parse("2026-12-21T12:00:00Z");
    private final StripeClient client = mock(StripeClient.class);
    private final V1Services v1 = mock(V1Services.class);
    private final SubscriptionService subscriptions = mock(SubscriptionService.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final InvoiceService invoices = mock(InvoiceService.class);
    private final CheckoutService checkout = mock(CheckoutService.class);
    private final com.stripe.service.checkout.SessionService sessions =
            mock(com.stripe.service.checkout.SessionService.class);
    private final ChargeService charges = mock(ChargeService.class);
    private final DisputeService disputes = mock(DisputeService.class);
    private StripeSubscriptionLifecycleGateway gateway;
    private Subscription subscription;
    private Customer customer;
    private Session session;
    private Invoice invoice;

    @BeforeEach
    void prepare() throws Exception {
        var billing = new StripeBillingGateway(false, Optional.of("sk_test_example"), Optional.empty(),
                "http://localhost:8080") {
            @Override StripeClient client() { return client; }
        };
        gateway = new StripeSubscriptionLifecycleGateway(billing);
        when(client.v1()).thenReturn(v1);
        when(v1.subscriptions()).thenReturn(subscriptions);
        when(v1.customers()).thenReturn(customers);
        when(v1.invoices()).thenReturn(invoices);
        when(v1.checkout()).thenReturn(checkout);
        when(checkout.sessions()).thenReturn(sessions);
        when(v1.charges()).thenReturn(charges);
        when(v1.disputes()).thenReturn(disputes);
        customer = new Customer();
        customer.setId("cus_test");
        customer.setLivemode(false);
        customer.setMetadata(Map.of("timereport_account_id", ACCOUNT.toString()));
        when(customers.retrieve("cus_test")).thenReturn(customer);
        subscription = subscription();
        when(subscriptions.retrieve("sub_test")).thenReturn(subscription);
        session = new Session();
        session.setId("cs_test_example");
        session.setLivemode(false);
        session.setStatus("complete");
        session.setMode("subscription");
        session.setSubscription("sub_test");
        session.setCustomer("cus_test");
        session.setClientReferenceId(ATTEMPT.toString());
        session.setMetadata(Map.of("timereport_account_id", ACCOUNT.toString(),
                "timereport_price_model_id", MODEL.toString()));
        when(sessions.list(any(SessionListParams.class))).thenReturn(page(List.of(session), false));
        invoice = invoice("in_paid", "paid");
        when(invoices.list(any(InvoiceListParams.class))).thenReturn(page(List.of(invoice), false));
    }

    @Test
    void readsVerifiedServicePeriodFromTheRecurringInvoiceLineRatherThanInvoiceEnvelope() {
        var result = gateway.fetch("sub_test");

        assertEquals(ACCOUNT, result.accountId());
        assertEquals(MODEL, result.priceModelId());
        assertEquals(ATTEMPT, result.checkoutAttemptId());
        assertEquals("cs_test_example", result.checkoutSessionId());
        assertEquals(SubscriptionPlan.MONTHLY, result.plan());
        assertEquals(BillingSubscription.State.ACTIVE, result.state());
        var paid = result.invoices().getFirst();
        assertEquals(BillingInvoice.State.PAID, paid.state());
        assertEquals(START, paid.periodStart());
        assertEquals(END, paid.periodEnd());
        assertEquals(START.plusSeconds(30), paid.paidAt());
        assertTrue(paid.renewal());
        assertEquals(12_375, paid.amountPaid());
    }

    @Test
    void supportsStageTwoSubscriptionsUsingCompletedCheckoutAsAttemptProof() {
        subscription.setMetadata(Map.of("timereport_account_id", ACCOUNT.toString(),
                "timereport_price_model_id", MODEL.toString()));
        assertEquals(ATTEMPT, gateway.fetch("sub_test").checkoutAttemptId());
    }

    @Test
    void unrelatedProductsNeverReturnAnEntitlementOrInvoiceHistory() {
        subscription.setMetadata(Map.of("other_product", "true"));
        var result = gateway.fetch("sub_test");
        assertNull(result.accountId());
        assertNull(result.priceModelId());
        assertNull(result.checkoutAttemptId());
        assertTrue(result.invoices().isEmpty());
        verifyNoInteractions(customers, invoices, sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"live-subscription", "live-customer", "customer-account", "checkout-account",
            "checkout-attempt", "quantity", "currency", "interval", "proration", "line-price",
            "line-subscription", "invoice-customer", "invoice-live", "invoice-unpaid", "invoice-paid-at"})
    void failsClosedWhenAuthoritativeReferencesOrPaidPeriodCannotBeVerified(String mismatch) {
        var item = subscription.getItems().getData().getFirst();
        var line = invoice.getLines().getData().getFirst();
        switch (mismatch) {
            case "live-subscription" -> subscription.setLivemode(true);
            case "live-customer" -> customer.setLivemode(true);
            case "customer-account" -> customer.setMetadata(Map.of("timereport_account_id", UUID.randomUUID().toString()));
            case "checkout-account" -> session.setMetadata(Map.of("timereport_account_id", UUID.randomUUID().toString(),
                    "timereport_price_model_id", MODEL.toString()));
            case "checkout-attempt" -> session.setClientReferenceId(UUID.randomUUID().toString());
            case "quantity" -> item.setQuantity(2L);
            case "currency" -> item.getPrice().setCurrency("eur");
            case "interval" -> item.getPrice().getRecurring().setInterval("week");
            case "proration" -> line.getParent().getSubscriptionItemDetails().setProration(true);
            case "line-price" -> line.getPricing().getPriceDetails().setPrice("price_other");
            case "line-subscription" -> line.getParent().getSubscriptionItemDetails().setSubscription("sub_other");
            case "invoice-customer" -> invoice.setCustomer("cus_other");
            case "invoice-live" -> invoice.setLivemode(true);
            case "invoice-unpaid" -> invoice.setAmountRemaining(100L);
            case "invoice-paid-at" -> invoice.getStatusTransitions().setPaidAt(null);
        }
        assertThrows(SubscriptionBillingUnavailable.class, () -> gateway.fetch("sub_test"));
    }

    @Test
    void refusesAmbiguousMultipleRecurringInvoiceLines() {
        var original = invoice.getLines().getData().getFirst();
        invoice.getLines().setData(List.of(original, original));
        assertThrows(SubscriptionBillingUnavailable.class, () -> gateway.fetch("sub_test"));
    }

    @Test
    void resolvesInvoiceEventsUsingTheRetrievedInvoiceNotPayloadAttributes() throws Exception {
        when(invoices.retrieve("in_paid")).thenReturn(invoice);
        var event = new BillingEvent("evt_test", "invoice.paid", "in_paid", START);
        var link = gateway.locate(event);
        assertEquals("cus_test", link.customerId());
        assertEquals("sub_test", link.subscriptionId());
    }

    @Test
    void refundAndDisputeNoticesAreLinkedToVerifiedCustomerForManualReview() throws Exception {
        var charge = new Charge();
        charge.setId("ch_test");
        charge.setCustomer("cus_test");
        charge.setLivemode(false);
        when(charges.retrieve("ch_test")).thenReturn(charge);
        var dispute = new Dispute();
        dispute.setId("dp_test");
        dispute.setLivemode(false);
        dispute.setCharge("ch_test");
        when(disputes.retrieve("dp_test")).thenReturn(dispute);

        var refund = gateway.locate(new BillingEvent("evt_refund", "charge.refunded", "ch_test", START));
        var review = gateway.locate(new BillingEvent("evt_dispute", "charge.dispute.created", "dp_test", START));
        assertEquals("cus_test", refund.customerId());
        assertEquals("REFUND_REQUIRES_REVIEW", refund.reviewKind());
        assertNull(refund.subscriptionId());
        assertEquals("DISPUTE_REQUIRES_REVIEW", review.reviewKind());
        verify(subscriptions, never()).cancel(anyString(), any(SubscriptionCancelParams.class), any(RequestOptions.class));
    }

    @Test
    void listsAllSubscriptionStatesAndDoesNotSilentlyTruncatePagination() throws Exception {
        when(subscriptions.list(any(SubscriptionListParams.class))).thenReturn(page(List.of(subscription), false));
        assertEquals(List.of("sub_test"), gateway.subscriptionIds("cus_test"));
        var params = ArgumentCaptor.forClass(SubscriptionListParams.class);
        verify(subscriptions).list(params.capture());
        assertEquals(SubscriptionListParams.Status.ALL, params.getValue().getStatus());

        when(subscriptions.list(any(SubscriptionListParams.class))).thenReturn(page(List.of(subscription), true));
        assertThrows(SubscriptionBillingUnavailable.class, () -> gateway.subscriptionIds("cus_test"));
    }

    @Test
    void terminationCancelsWithoutFinalChargeAndClosesOpenAndSubscriptionDraftInvoices() throws Exception {
        var canceled = subscription();
        canceled.setStatus("canceled");
        when(subscriptions.cancel(eq("sub_test"), any(SubscriptionCancelParams.class), any(RequestOptions.class)))
                .thenReturn(canceled);
        var open = invoice("in_open", "open");
        var draft = invoice("in_draft", "draft");
        when(invoices.list(any(InvoiceListParams.class))).thenReturn(page(List.of(invoice, open, draft), false));
        when(invoices.retrieve("in_paid")).thenReturn(invoice);
        when(invoices.retrieve("in_open")).thenReturn(open);
        when(invoices.retrieve("in_draft")).thenReturn(draft);
        when(invoices.finalizeInvoice(eq("in_draft"), any(InvoiceFinalizeInvoiceParams.class), any(RequestOptions.class)))
                .thenReturn(invoice("in_draft", "open"));
        when(invoices.voidInvoice(eq("in_open"), any(RequestOptions.class))).thenReturn(invoice("in_open", "void"));
        when(invoices.voidInvoice(eq("in_draft"), any(RequestOptions.class))).thenReturn(invoice("in_draft", "void"));

        gateway.terminate("sub_test", "close-sub-test");

        var cancellation = ArgumentCaptor.forClass(SubscriptionCancelParams.class);
        verify(subscriptions).cancel(eq("sub_test"), cancellation.capture(), any(RequestOptions.class));
        assertEquals(false, cancellation.getValue().getInvoiceNow());
        assertEquals(false, cancellation.getValue().getProrate());
        var update = ArgumentCaptor.forClass(InvoiceUpdateParams.class);
        verify(invoices).update(eq("in_draft"), update.capture(), any(RequestOptions.class));
        assertEquals(false, update.getValue().getAutoAdvance());
        verify(invoices, never()).delete(anyString());
        verify(invoices, never()).voidInvoice(eq("in_paid"), any(RequestOptions.class));
        var voidKey = ArgumentCaptor.forClass(RequestOptions.class);
        verify(invoices).voidInvoice(eq("in_open"), voidKey.capture());
        assertEquals("close-sub-test-void-in_open", voidKey.getValue().getIdempotencyKey());
    }

    @Test
    void terminationResumesInvoiceCleanupAfterCancellationAlreadySucceeded() throws Exception {
        subscription.setStatus("canceled");
        when(invoices.retrieve("in_paid")).thenReturn(invoice);
        gateway.terminate("sub_test", "retry-close");
        verify(subscriptions, never()).cancel(anyString(), any(SubscriptionCancelParams.class), any(RequestOptions.class));
    }

    private Subscription subscription() {
        var recurring = new Price.Recurring();
        recurring.setInterval("month");
        recurring.setIntervalCount(1L);
        var price = new Price();
        price.setId("price_test");
        price.setLivemode(false);
        price.setCurrency("sek");
        price.setRecurring(recurring);
        var item = new SubscriptionItem();
        item.setId("si_test");
        item.setSubscription("sub_test");
        item.setPrice(price);
        item.setQuantity(1L);
        item.setCurrentPeriodStart(START.getEpochSecond());
        item.setCurrentPeriodEnd(END.getEpochSecond());
        var items = new SubscriptionItemCollection();
        items.setData(List.of(item));
        items.setHasMore(false);
        var result = new Subscription();
        result.setId("sub_test");
        result.setCustomer("cus_test");
        result.setStatus("active");
        result.setLivemode(false);
        result.setMetadata(Map.of("timereport_account_id", ACCOUNT.toString(),
                "timereport_price_model_id", MODEL.toString(), "timereport_checkout_attempt_id", ATTEMPT.toString()));
        result.setItems(items);
        result.setCancelAtPeriodEnd(false);
        return result;
    }

    private Invoice invoice(String id, String state) {
        var details = new Invoice.Parent.SubscriptionDetails();
        details.setSubscription("sub_test");
        var parent = new Invoice.Parent();
        parent.setType("subscription_details");
        parent.setSubscriptionDetails(details);
        var period = new InvoiceLineItem.Period();
        period.setStart(START.getEpochSecond());
        period.setEnd(END.getEpochSecond());
        var price = new InvoiceLineItem.Pricing.PriceDetails();
        price.setPrice("price_test");
        var pricing = new InvoiceLineItem.Pricing();
        pricing.setPriceDetails(price);
        var subscriptionItem = new InvoiceLineItem.Parent.SubscriptionItemDetails();
        subscriptionItem.setSubscription("sub_test");
        subscriptionItem.setSubscriptionItem("si_test");
        subscriptionItem.setProration(false);
        var lineParent = new InvoiceLineItem.Parent();
        lineParent.setType("subscription_item_details");
        lineParent.setSubscriptionItemDetails(subscriptionItem);
        var line = new InvoiceLineItem();
        line.setId("il_test");
        line.setCurrency("sek");
        line.setQuantity(1L);
        line.setPeriod(period);
        line.setPricing(pricing);
        line.setParent(lineParent);
        var lines = new InvoiceLineItemCollection();
        lines.setData(List.of(line));
        lines.setHasMore(false);
        var transitions = new Invoice.StatusTransitions();
        transitions.setPaidAt(START.plusSeconds(30).getEpochSecond());
        var result = new Invoice();
        result.setId(id);
        result.setStatus(state);
        result.setLivemode(false);
        result.setCustomer("cus_test");
        result.setCurrency("sek");
        result.setParent(parent);
        result.setLines(lines);
        result.setAmountPaid("paid".equals(state) ? 12_375L : 0L);
        result.setAmountRemaining("paid".equals(state) ? 0L : 12_375L);
        result.setTotal(12_375L);
        result.setStatusTransitions(transitions);
        result.setBillingReason("subscription_cycle");
        result.setPeriodStart(START.minusSeconds(86_400).getEpochSecond());
        result.setPeriodEnd(START.minusSeconds(3_600).getEpochSecond());
        return result;
    }

    private <T extends com.stripe.model.StripeObject & com.stripe.model.HasId> StripeCollection<T> page(List<T> data, boolean more) {
        var result = new StripeCollection<T>();
        result.setData(data);
        result.setHasMore(more);
        return result;
    }
}
