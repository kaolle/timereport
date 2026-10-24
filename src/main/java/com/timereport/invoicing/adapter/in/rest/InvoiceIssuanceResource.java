package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.application.port.in.InvoiceIssuance;
import com.timereport.invoicing.application.port.out.InvoiceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/invoicing/invoices")
@Consumes(MediaType.APPLICATION_JSON)
public class InvoiceIssuanceResource {
    private final InvoiceIssuance issuance;
    private final InvoiceRepository invoices;

    public InvoiceIssuanceResource(InvoiceIssuance issuance, InvoiceRepository invoices) {
        this.issuance = issuance;
        this.invoices = invoices;
    }

    @POST
    public Response issue(@NotNull @Valid IssueInvoiceRequest request) {
        var document = issuance.issue(request.month(), request.customerId(),
                request.adjustments().stream().map(InvoiceLineAdjustmentRequest::toDomain).toList());
        int number = document.invoice().invoiceNumber();
        return Response.ok(document.pdf(), "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"Faktura-" + number + ".pdf\"")
                .header("X-Invoice-Number", number)
                .header("Cache-Control", "no-store")
                .build();
    }

    @GET
    @Path("/{invoiceNumber}/pdf")
    @Produces("application/pdf")
    public Response pdf(@PathParam("invoiceNumber") @NotNull @Min(1) Integer invoiceNumber) {
        return invoices.findPdf(invoiceNumber).<Response>map(pdf -> Response.ok(pdf, "application/pdf")
                        .header("Content-Disposition", "attachment; filename=\"Faktura-" + invoiceNumber + ".pdf\"")
                        .header("Cache-Control", "no-store").build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }
}
