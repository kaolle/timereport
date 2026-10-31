package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.application.port.in.InvoiceIssuance;
import com.timereport.invoicing.application.port.in.IssuedInvoices;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/invoicing/invoices")
@Consumes(MediaType.APPLICATION_JSON)
public class InvoiceIssuanceResource {
    private final InvoiceIssuance issuance;
    private final IssuedInvoices invoices;

    public InvoiceIssuanceResource(InvoiceIssuance issuance, IssuedInvoices invoices) {
        this.issuance = issuance;
        this.invoices = invoices;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @org.jboss.resteasy.reactive.ResponseHeader(name = "Cache-Control", value = "no-store")
    public List<InvoiceSummaryResponse> list() {
        return invoices.list().stream().map(InvoiceSummaryResponse::from).toList();
    }

    @POST
    @Path("/{invoiceNumber}/email")
    @Produces(MediaType.APPLICATION_JSON)
    @org.jboss.resteasy.reactive.ResponseHeader(name = "Cache-Control", value = "no-store")
    public InvoiceDeliveryResponse email(@PathParam("invoiceNumber") @Min(1) int number, @NotNull @Valid SendInvoiceRequest request) {
        return InvoiceDeliveryResponse.from(invoices.send(number, request.recipient(), request.cc(), request.bcc(), request.message(), request.requestId()));
    }

    @GET
    @Path("/{invoiceNumber}/delivery-defaults")
    @Produces(MediaType.APPLICATION_JSON)
    @org.jboss.resteasy.reactive.ResponseHeader(name = "Cache-Control", value = "no-store")
    public InvoiceDeliveryDefaultsResponse deliveryDefaults(@PathParam("invoiceNumber") @Min(1) int number) {
        return InvoiceDeliveryDefaultsResponse.from(invoices.deliveryDefaults(number));
    }

    @PUT
    @Path("/{invoiceNumber}/status")
    @Produces(MediaType.APPLICATION_JSON)
    @org.jboss.resteasy.reactive.ResponseHeader(name = "Cache-Control", value = "no-store")
    public InvoiceStatusResponse status(@PathParam("invoiceNumber") @Min(1) int number, @NotNull @Valid SetInvoiceStatusRequest request) {
        return InvoiceStatusResponse.from(invoices.setStatus(number, request.sent(), request.paid(), request.expectedVersion()));
    }

    @POST
    @Produces("application/pdf")
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
        return Response.ok(invoices.get(invoiceNumber).pdf(), "application/pdf")
                        .header("Content-Disposition", "attachment; filename=\"Faktura-" + invoiceNumber + ".pdf\"")
                .header("Cache-Control", "no-store").build();
    }
}
