package com.timereport.subscription.adapter.out.stripe;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoiceFinalizeInvoiceParams;
import com.stripe.param.InvoiceUpdateParams;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.checkout.SessionListParams;
import com.timereport.subscription.application.port.out.SubscriptionLifecycleGateway;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.domain.BillingEvent;
import com.timereport.subscription.domain.BillingEventLink;
import com.timereport.subscription.domain.BillingSubscription;
import com.timereport.subscription.domain.SubscriptionPlan;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class StripeSubscriptionLifecycleGateway implements SubscriptionLifecycleGateway {
    static final int MAX_PAGES = 20;
    private static final long PAGE_SIZE = 100;
    private final StripeBillingGateway billing;

    public StripeSubscriptionLifecycleGateway(StripeBillingGateway billing) {
        this.billing = billing;
    }

    @Override
    public BillingEventLink locate(BillingEvent event) {
        billing.requirePricingConfigured();
        try {
            if (event.type().startsWith("checkout.session.")) {
                var session = billing.client().v1().checkout().sessions().retrieve(event.objectId());
                requireTest(session.getLivemode());
                if (!event.objectId().equals(session.getId()) || !"subscription".equals(session.getMode()))
                    throw invalid();
                return new BillingEventLink(session.getCustomer(), session.getSubscription(), null);
            }
            if (event.type().startsWith("invoice.")) {
                var invoice = billing.client().v1().invoices().retrieve(event.objectId());
                requireTest(invoice.getLivemode());
                if (!event.objectId().equals(invoice.getId())) throw invalid();
                return new BillingEventLink(invoice.getCustomer(), StripeSubscriptionInvoices.subscriptionId(invoice), null);
            }
            if (event.type().startsWith("customer.subscription.")) {
                var subscription = retrieve(event.objectId());
                return new BillingEventLink(subscription.getCustomer(), subscription.getId(), null);
            }
            if (event.type().equals("charge.refunded")) {
                var charge = billing.client().v1().charges().retrieve(event.objectId());
                requireTest(charge.getLivemode());
                if (!event.objectId().equals(charge.getId())) throw invalid();
                return new BillingEventLink(charge.getCustomer(), null, "REFUND_REQUIRES_REVIEW");
            }
            if (event.type().startsWith("charge.dispute.")) {
                var dispute = billing.client().v1().disputes().retrieve(event.objectId());
                requireTest(dispute.getLivemode());
                if (!event.objectId().equals(dispute.getId()) || dispute.getCharge() == null) throw invalid();
                var charge = billing.client().v1().charges().retrieve(dispute.getCharge());
                requireTest(charge.getLivemode());
                if (!dispute.getCharge().equals(charge.getId())) throw invalid();
                return new BillingEventLink(charge.getCustomer(), null, "DISPUTE_REQUIRES_REVIEW");
            }
            return null;
        } catch (StripeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public BillingSubscription fetch(String subscriptionId) {
        billing.requirePricingConfigured();
        try {
            var subscription = retrieve(subscriptionId);
            var state = state(subscription.getStatus());
            var metadata = subscription.getMetadata();
            UUID account = optionalUuid(metadata, "timereport_account_id");
            UUID priceModel = optionalUuid(metadata, "timereport_price_model_id");
            if (account == null && priceModel == null) {
                // A different product on this Stripe Customer never grants app access.
                return BillingSubscription.builder().id(subscription.getId()).customerId(subscription.getCustomer())
                        .state(state).invoices(List.of()).build();
            }
            if (account == null || priceModel == null) throw invalid();
            requireCustomer(subscription.getCustomer(), account);
            if (subscription.getItems() == null || Boolean.TRUE.equals(subscription.getItems().getHasMore())
                    || subscription.getItems().getData() == null || subscription.getItems().getData().size() != 1)
                throw invalid();
            var item = subscription.getItems().getData().getFirst();
            var price = item.getPrice();
            if (!subscriptionId.equals(item.getSubscription()) || !Long.valueOf(1).equals(item.getQuantity())
                    || price == null || price.getId() == null || !price.getId().startsWith("price_")
                    || !Boolean.FALSE.equals(price.getLivemode()) || !"sek".equals(price.getCurrency())
                    || price.getRecurring() == null || !Long.valueOf(1).equals(price.getRecurring().getIntervalCount()))
                throw invalid();
            SubscriptionPlan plan = switch (price.getRecurring().getInterval()) {
                case "month" -> SubscriptionPlan.MONTHLY;
                case "year" -> SubscriptionPlan.YEARLY;
                default -> throw invalid();
            };
            var checkout = completeCheckout(subscription, account, priceModel);
            UUID metadataAttempt = optionalUuid(metadata, "timereport_checkout_attempt_id");
            UUID checkoutAttempt = checkout == null ? metadataAttempt : parseUuid(checkout.getClientReferenceId());
            if (checkout != null && metadataAttempt != null && !metadataAttempt.equals(checkoutAttempt)) throw invalid();
            var invoices = new StripeSubscriptionInvoices(billing.client()).read(subscriptionId,
                    subscription.getCustomer(), price.getId(), item.getId());
            return BillingSubscription.builder().id(subscription.getId()).customerId(subscription.getCustomer())
                    .accountId(account).priceModelId(priceModel).checkoutAttemptId(checkoutAttempt)
                    .checkoutSessionId(checkout == null ? null : checkout.getId()).priceId(price.getId()).plan(plan)
                    .state(state).periodStart(instant(item.getCurrentPeriodStart())).periodEnd(instant(item.getCurrentPeriodEnd()))
                    .cancelAtPeriodEnd(Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()))
                    .endedAt(instant(subscription.getEndedAt())).invoices(invoices).build();
        } catch (StripeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public List<String> subscriptionIds(String customerId) {
        billing.requirePricingConfigured();
        requirePrefix(customerId, "cus_");
        try {
            var ids = new ArrayList<String>();
            String after = null;
            for (int pageIndex = 0; pageIndex < MAX_PAGES; pageIndex++) {
                var params = SubscriptionListParams.builder().setCustomer(customerId)
                        .setStatus(SubscriptionListParams.Status.ALL).setLimit(PAGE_SIZE).setStartingAfter(after).build();
                var page = billing.client().v1().subscriptions().list(params);
                if (page == null || page.getData() == null) throw invalid();
                for (var subscription : page.getData()) {
                    requireTest(subscription.getLivemode());
                    if (!customerId.equals(subscription.getCustomer())) throw invalid();
                    requirePrefix(subscription.getId(), "sub_");
                    ids.add(subscription.getId());
                }
                if (!Boolean.TRUE.equals(page.getHasMore())) return List.copyOf(ids);
                if (page.getData().isEmpty()) throw invalid();
                after = page.getData().getLast().getId();
            }
            throw invalid();
        } catch (StripeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void terminate(String subscriptionId, String idempotencyKey) {
        billing.requirePricingConfigured();
        try {
            var subscription = retrieve(subscriptionId);
            UUID account = optionalUuid(subscription.getMetadata(), "timereport_account_id");
            if (account == null || optionalUuid(subscription.getMetadata(), "timereport_price_model_id") == null)
                throw invalid();
            requireCustomer(subscription.getCustomer(), account);
            if (!"canceled".equals(subscription.getStatus()) && !"incomplete_expired".equals(subscription.getStatus())) {
                var params = SubscriptionCancelParams.builder().setInvoiceNow(false).setProrate(false).build();
                var canceled = billing.client().v1().subscriptions().cancel(subscriptionId, params,
                        options(idempotencyKey + "-cancel"));
                requireTest(canceled.getLivemode());
                if (!subscriptionId.equals(canceled.getId()) || !subscription.getCustomer().equals(canceled.getCustomer())
                        || !"canceled".equals(canceled.getStatus())) throw invalid();
            }
            // Cancellation alone pauses invoices; void them so retries cannot collect an old period.
            var reader = new StripeSubscriptionInvoices(billing.client());
            for (var listed : reader.list(subscriptionId, subscription.getCustomer())) {
                var invoice = billing.client().v1().invoices().retrieve(listed.getId());
                reader.requireInvoice(invoice, subscriptionId, subscription.getCustomer());
                if ("paid".equals(invoice.getStatus()) || "void".equals(invoice.getStatus())) continue;
                if (!("draft".equals(invoice.getStatus()) || "open".equals(invoice.getStatus())
                        || "uncollectible".equals(invoice.getStatus()))) throw invalid();
                if ("draft".equals(invoice.getStatus())) {
                    // Subscription drafts cannot be deleted. Finalize without automatic collection, then void.
                    billing.client().v1().invoices().update(invoice.getId(),
                            InvoiceUpdateParams.builder().setAutoAdvance(false).build(),
                            options(idempotencyKey + "-pause-" + invoice.getId()));
                    invoice = billing.client().v1().invoices().finalizeInvoice(invoice.getId(),
                            InvoiceFinalizeInvoiceParams.builder().setAutoAdvance(false).build(),
                            options(idempotencyKey + "-finalize-" + invoice.getId()));
                    reader.requireInvoice(invoice, subscriptionId, subscription.getCustomer());
                    if ("paid".equals(invoice.getStatus())) continue;
                }
                var closed = billing.client().v1().invoices().voidInvoice(invoice.getId(),
                        options(idempotencyKey + "-void-" + invoice.getId()));
                reader.requireInvoice(closed, subscriptionId, subscription.getCustomer());
                if (!"void".equals(closed.getStatus())) throw invalid();
            }
        } catch (StripeException exception) {
            throw unavailable(exception);
        }
    }

    private Session completeCheckout(Subscription subscription, UUID account, UUID model) throws StripeException {
        Session found = null;
        String after = null;
        for (int index = 0; index < MAX_PAGES; index++) {
            var params = SessionListParams.builder().setCustomer(subscription.getCustomer())
                    .setSubscription(subscription.getId()).setLimit(PAGE_SIZE).setStartingAfter(after).build();
            var page = billing.client().v1().checkout().sessions().list(params);
            if (page == null || page.getData() == null) throw invalid();
            for (var session : page.getData()) {
                requireTest(session.getLivemode());
                if (!"complete".equals(session.getStatus())) continue;
                if (found != null || !"subscription".equals(session.getMode())
                        || !subscription.getId().equals(session.getSubscription())
                        || !subscription.getCustomer().equals(session.getCustomer())
                        || !account.equals(optionalUuid(session.getMetadata(), "timereport_account_id"))
                        || !model.equals(optionalUuid(session.getMetadata(), "timereport_price_model_id"))) throw invalid();
                requirePrefix(session.getId(), "cs_test_");
                parseUuid(session.getClientReferenceId());
                found = session;
            }
            if (!Boolean.TRUE.equals(page.getHasMore())) return found;
            if (page.getData().isEmpty()) throw invalid();
            after = page.getData().getLast().getId();
        }
        throw invalid();
    }

    private Subscription retrieve(String id) throws StripeException {
        requirePrefix(id, "sub_");
        var subscription = billing.client().v1().subscriptions().retrieve(id);
        requireTest(subscription.getLivemode());
        if (!id.equals(subscription.getId())) throw invalid();
        requirePrefix(subscription.getCustomer(), "cus_");
        return subscription;
    }

    private void requireCustomer(String customerId, UUID accountId) throws StripeException {
        Customer customer = billing.client().v1().customers().retrieve(customerId);
        requireTest(customer.getLivemode());
        if (!customerId.equals(customer.getId()) || Boolean.TRUE.equals(customer.getDeleted())
                || !accountId.equals(optionalUuid(customer.getMetadata(), "timereport_account_id"))) throw invalid();
    }

    private BillingSubscription.State state(String state) {
        if (state == null) throw invalid();
        return switch (state) {
            case "incomplete", "trialing" -> BillingSubscription.State.INCOMPLETE;
            case "active" -> BillingSubscription.State.ACTIVE;
            case "past_due" -> BillingSubscription.State.PAST_DUE;
            case "canceled", "incomplete_expired" -> BillingSubscription.State.CANCELED;
            case "unpaid" -> BillingSubscription.State.UNPAID;
            case "paused" -> BillingSubscription.State.PAUSED;
            default -> throw invalid();
        };
    }

    private UUID optionalUuid(Map<String, String> metadata, String key) {
        return metadata == null || metadata.get(key) == null ? null : parseUuid(metadata.get(key));
    }

    private UUID parseUuid(String value) {
        try { return UUID.fromString(Objects.requireNonNull(value)); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw invalid(); }
    }

    private Instant instant(Long seconds) {
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    private void requirePrefix(String id, String prefix) {
        if (id == null || !id.startsWith(prefix) || id.length() > 255) throw invalid();
    }

    private void requireTest(Boolean livemode) {
        if (!Boolean.FALSE.equals(livemode)) throw invalid();
    }

    private RequestOptions options(String key) {
        if (key == null || key.isBlank() || key.length() > 255) throw invalid();
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }

    private SubscriptionBillingUnavailable invalid() {
        return new SubscriptionBillingUnavailable("Stripes abonnemangsuppgifter kunde inte verifieras.");
    }

    private SubscriptionBillingUnavailable unavailable(StripeException exception) {
        return new SubscriptionBillingUnavailable("Stripe kunde inte stämma av abonnemanget. Försök igen senare.", exception);
    }
}
