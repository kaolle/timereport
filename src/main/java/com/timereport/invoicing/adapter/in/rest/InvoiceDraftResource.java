package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.application.port.in.InvoiceDrafts;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("/api/invoicing/draft")
@Produces(MediaType.APPLICATION_JSON)
public class InvoiceDraftResource {
    private final InvoiceDrafts drafts;

    public InvoiceDraftResource(InvoiceDrafts drafts) { this.drafts = drafts; }

    @GET
    public InvoiceDraftResponse get(@QueryParam("month") String month, @QueryParam("customerId") UUID customerId) {
        return InvoiceDraftResponse.from(drafts.create(month, customerId));
    }
}
