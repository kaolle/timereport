package com.timereport.invoicing.adapter.in.rest;

import com.timereport.authentication.application.port.in.AccountIdentity;
import com.timereport.subscription.application.port.in.SubscriptionAccess;
import com.timereport.subscription.configuration.InvoicingRollout;
import com.timereport.subscription.domain.SubscriptionSnapshot;
import com.timereport.subscription.domain.SubscriptionState;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class InvoicingRolloutFilterTest {
    private final UUID accountId = UUID.randomUUID();
    private final AccountIdentity identity = mock(AccountIdentity.class);
    private final SubscriptionAccess subscriptions = mock(SubscriptionAccess.class);
    private final ContainerRequestContext request = mock(ContainerRequestContext.class);
    private final UriInfo uri = mock(UriInfo.class);

    @Test
    void blocksNonOwnerForReadAndWriteButKeepsTimeLabelsAvailable() {
        when(identity.accountId()).thenReturn(accountId);
        when(subscriptions.status(accountId)).thenReturn(snapshot(false));
        when(request.getUriInfo()).thenReturn(uri);
        var filter = new InvoicingRolloutFilter(identity, subscriptions, new InvoicingRollout(true));

        when(uri.getPath()).thenReturn("/api/invoicing/profile");
        filter.filter(request);
        when(uri.getPath()).thenReturn("api/invoicing/invoices");
        filter.filter(request);
        var responses = ArgumentCaptor.forClass(Response.class);
        verify(request, times(2)).abortWith(responses.capture());
        for (var response : responses.getAllValues()) {
            assertEquals(403, response.getStatus());
            assertEquals("INVOICING_COMING_SOON", ((InvoicingConflictResponse) response.getEntity()).code());
        }

        when(uri.getPath()).thenReturn("api/invoicing/customer-code-labels");
        when(request.getMethod()).thenReturn("GET");
        filter.filter(request);
        verify(request, times(2)).abortWith(any());
    }

    @Test
    void ownerCanUseAllInvoicingEndpoints() {
        when(identity.accountId()).thenReturn(accountId);
        when(subscriptions.status(accountId)).thenReturn(snapshot(true));
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("api/invoicing/draft/pdf");
        new InvoicingRolloutFilter(identity, subscriptions, new InvoicingRollout(true)).filter(request);
        verify(request, never()).abortWith(any());
    }

    private SubscriptionSnapshot snapshot(boolean owner) {
        return SubscriptionSnapshot.builder().accountId(accountId).state(SubscriptionState.NONE)
                .ownerAccess(owner).build();
    }
}
