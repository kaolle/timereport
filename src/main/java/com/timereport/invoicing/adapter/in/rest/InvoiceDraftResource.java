package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.application.port.in.InvoiceDrafts;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

import com.timereport.invoicing.application.port.in.InvoiceIssuance;

@Path("/api/invoicing/draft")
@Produces(MediaType.APPLICATION_JSON)
public class InvoiceDraftResource {
    private final InvoiceDrafts drafts;
    private final InvoiceIssuance issuance;

    public InvoiceDraftResource(InvoiceDrafts drafts, InvoiceIssuance issuance) {
        this.drafts = drafts;
        this.issuance = issuance;
    }

    @GET
    public InvoiceDraftResponse get(@QueryParam("month") String month, @QueryParam("customerId") UUID customerId) {
        return InvoiceDraftResponse.from(drafts.create(month, customerId));
    }

    @POST
    @Path("/pdf")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/pdf")
    public Response pdf(@NotNull @Valid InvoiceDraftPdfRequest request) {
        var document = issuance.preview(request.month(), request.customerId(),
                request.adjustments().stream().map(InvoiceLineAdjustmentRequest::toDomain).toList());
        return Response.ok(document.pdf(), "application/pdf")
                .header("Content-Disposition", "inline; filename=Faktura-utkast.pdf")
                .header("Cache-Control", "no-store")
                .build();
    }
}
