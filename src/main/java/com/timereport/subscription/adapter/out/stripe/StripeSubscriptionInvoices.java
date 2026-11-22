package com.timereport.subscription.adapter.out.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.param.InvoiceLineItemListParams;
import com.stripe.param.InvoiceListParams;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.domain.BillingInvoice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class StripeSubscriptionInvoices {
    private final StripeClient client;

    StripeSubscriptionInvoices(StripeClient client) {
        this.client = client;
    }

    List<BillingInvoice> read(String subscriptionId, String customerId, String priceId, String itemId)
            throws StripeException {
        var invoices = new ArrayList<BillingInvoice>();
        for (var invoice : list(subscriptionId, customerId)) {
            BillingInvoice.State state = switch (invoice.getStatus()) {
                case "draft" -> BillingInvoice.State.DRAFT;
                case "open" -> BillingInvoice.State.OPEN;
                case "paid" -> BillingInvoice.State.PAID;
                case "void" -> BillingInvoice.State.VOID;
                case "uncollectible" -> BillingInvoice.State.UNCOLLECTIBLE;
                default -> throw invalid();
            };
            InvoiceLineItem selected = null;
            for (var line : lines(invoice)) {
                var parent = line.getParent();
                if (parent == null || !"subscription_item_details".equals(parent.getType())) continue;
                var details = parent.getSubscriptionItemDetails();
                if (details == null || !subscriptionId.equals(details.getSubscription())) throw invalid();
                if (Boolean.TRUE.equals(details.getProration())) throw invalid();
                if (!itemId.equals(details.getSubscriptionItem()) || line.getPricing() == null
                        || line.getPricing().getPriceDetails() == null
                        || !priceId.equals(line.getPricing().getPriceDetails().getPrice())
                        || !"sek".equals(line.getCurrency()) || !Long.valueOf(1).equals(line.getQuantity())) throw invalid();
                if (selected != null) throw invalid();
                selected = line;
            }
            var period = selected == null ? null : selected.getPeriod();
            Instant start = period == null || period.getStart() == null ? null : Instant.ofEpochSecond(period.getStart());
            Instant end = period == null || period.getEnd() == null ? null : Instant.ofEpochSecond(period.getEnd());
            Instant paidAt = invoice.getStatusTransitions() == null || invoice.getStatusTransitions().getPaidAt() == null
                    ? null : Instant.ofEpochSecond(invoice.getStatusTransitions().getPaidAt());
            if (state == BillingInvoice.State.PAID && (selected == null || start == null || end == null
                    || !end.isAfter(start) || paidAt == null || invoice.getAmountPaid() == null
                    || invoice.getTotal() == null || invoice.getAmountRemaining() == null
                    || invoice.getAmountRemaining() != 0 || invoice.getAmountPaid() < invoice.getTotal())) throw invalid();
            invoices.add(BillingInvoice.builder().id(invoice.getId()).subscriptionId(subscriptionId).customerId(customerId)
                    .priceId(selected == null ? null : priceId).state(state).periodStart(start).periodEnd(end)
                    .amountPaid(invoice.getAmountPaid() == null ? 0 : invoice.getAmountPaid())
                    .total(invoice.getTotal() == null ? 0 : invoice.getTotal()).paidAt(paidAt)
                    .renewal("subscription_cycle".equals(invoice.getBillingReason())).build());
        }
        return List.copyOf(invoices);
    }

    List<Invoice> list(String subscriptionId, String customerId) throws StripeException {
        var invoices = new ArrayList<Invoice>();
        String after = null;
        for (int index = 0; index < StripeSubscriptionLifecycleGateway.MAX_PAGES; index++) {
            var params = InvoiceListParams.builder().setSubscription(subscriptionId).setCustomer(customerId)
                    .setLimit(100L).setStartingAfter(after).build();
            var page = client.v1().invoices().list(params);
            if (page == null || page.getData() == null) throw invalid();
            for (var invoice : page.getData()) {
                requireInvoice(invoice, subscriptionId, customerId);
                invoices.add(invoice);
            }
            if (!Boolean.TRUE.equals(page.getHasMore())) return List.copyOf(invoices);
            if (page.getData().isEmpty()) throw invalid();
            after = page.getData().getLast().getId();
        }
        throw invalid();
    }

    void requireInvoice(Invoice invoice, String subscriptionId, String customerId) {
        if (invoice == null || invoice.getId() == null || !invoice.getId().startsWith("in_")
                || !Boolean.FALSE.equals(invoice.getLivemode()) || !customerId.equals(invoice.getCustomer())
                || !subscriptionId.equals(subscriptionId(invoice)) || !"sek".equals(invoice.getCurrency())) throw invalid();
    }

    static String subscriptionId(Invoice invoice) {
        return invoice.getParent() == null || invoice.getParent().getSubscriptionDetails() == null
                ? null : invoice.getParent().getSubscriptionDetails().getSubscription();
    }

    private List<InvoiceLineItem> lines(Invoice invoice) throws StripeException {
        var initial = invoice.getLines();
        if (initial != null && initial.getData() != null && !Boolean.TRUE.equals(initial.getHasMore()))
            return List.copyOf(initial.getData());
        var lines = new ArrayList<InvoiceLineItem>();
        String after = null;
        for (int index = 0; index < StripeSubscriptionLifecycleGateway.MAX_PAGES; index++) {
            var page = client.v1().invoices().lineItems().list(invoice.getId(), InvoiceLineItemListParams.builder()
                    .setLimit(100L).setStartingAfter(after).build());
            if (page == null || page.getData() == null) throw invalid();
            lines.addAll(page.getData());
            if (!Boolean.TRUE.equals(page.getHasMore())) return List.copyOf(lines);
            if (page.getData().isEmpty()) throw invalid();
            after = page.getData().getLast().getId();
        }
        throw invalid();
    }

    private SubscriptionBillingUnavailable invalid() {
        return new SubscriptionBillingUnavailable("Stripes fakturaperiod kunde inte verifieras.");
    }
}
