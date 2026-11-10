package com.timereport.subscription.adapter.in.rest;

import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionSnapshot;
import com.timereport.subscription.domain.SubscriptionState;
import lombok.Builder;

import java.time.Instant;

@Builder
public record SubscriptionStatusResponse(SubscriptionPlan plan, SubscriptionState status, Instant paidUntil,
                                         Instant graceUntil, boolean cancelAtPeriodEnd, boolean ownerAccess,
                                         boolean entitled, boolean invoicingAvailable) {
    public static SubscriptionStatusResponse from(SubscriptionSnapshot snapshot, Instant now,
                                                  boolean invoicingAvailable) {
        return SubscriptionStatusResponse.builder().plan(snapshot.plan()).status(snapshot.state())
                .paidUntil(snapshot.paidUntil()).graceUntil(snapshot.graceUntil())
                .cancelAtPeriodEnd(snapshot.cancelAtPeriodEnd()).ownerAccess(snapshot.ownerAccess())
                .entitled(snapshot.canInvoiceAt(now)).invoicingAvailable(invoicingAvailable).build();
    }
}
