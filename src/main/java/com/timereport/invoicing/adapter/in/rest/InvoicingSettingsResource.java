package com.timereport.invoicing.adapter.in.rest;

import com.timereport.invoicing.application.port.in.InvoicingSettings;
import com.timereport.invoicing.application.port.in.InvoiceNumberingSettings;
import com.timereport.invoicing.domain.BillingCustomer;
import com.timereport.invoicing.domain.CustomerCodeBilling;
import com.timereport.invoicing.domain.SellerBillingProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/api/invoicing")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InvoicingSettingsResource {
    private final InvoicingSettings settings;
    private final InvoiceNumberingSettings numbering;

    public InvoicingSettingsResource(InvoicingSettings settings, InvoiceNumberingSettings numbering) {
        this.settings = settings;
        this.numbering = numbering;
    }

    @GET
    @Path("/invoice-numbering")
    public InvoiceNumberingResponse invoiceNumbering() {
        return InvoiceNumberingResponse.from(numbering.get());
    }

    @PUT
    @Path("/invoice-numbering")
    public InvoiceNumberingResponse saveInvoiceNumbering(@NotNull @Valid InvoiceNumberingRequest request) {
        return InvoiceNumberingResponse.from(numbering.set(request.nextNumber()));
    }

    @GET
    @Path("/profile")
    public Response profile() {
        return settings.profile().<Response>map(p -> Response.ok(SellerBillingProfileResponse.from(p)).build())
                .orElseGet(() -> Response.noContent().build());
    }

    @PUT
    @Path("/profile")
    public SellerBillingProfileResponse saveProfile(@NotNull @Valid SaveSellerBillingProfileRequest request) {
        var profile = SellerBillingProfile.builder().sellerName(request.sellerName()).addressLine(request.addressLine())
                .postalCode(request.postalCode()).city(request.city()).country(request.country())
                .organizationNumber(request.organizationNumber()).vatNumber(request.vatNumber()).bankgiro(request.bankgiro())
                .email(request.email()).currencyCode(request.currencyCode()).paymentTermsDays(request.paymentTermsDays())
                .defaultVatPercent(request.defaultVatPercent()).fTaxRegistered(request.fTaxRegistered()).build();
        return SellerBillingProfileResponse.from(settings.saveProfile(profile));
    }

    @GET
    @Path("/customers")
    public List<BillingCustomerResponse> customers() {
        return settings.customers().stream().map(BillingCustomerResponse::from).toList();
    }

    @POST
    @Path("/customers")
    public Response createCustomer(@NotNull @Valid SaveBillingCustomerRequest request) {
        BillingCustomer customer = customer(UUID.randomUUID(), request);
        BillingCustomer created = settings.createCustomer(customer);
        return Response.status(Response.Status.CREATED).entity(BillingCustomerResponse.from(created)).build();
    }

    @PUT
    @Path("/customers/{id}")
    public BillingCustomerResponse updateCustomer(@PathParam("id") UUID id, @NotNull @Valid SaveBillingCustomerRequest request) {
        return BillingCustomerResponse.from(settings.updateCustomer(customer(id, request)));
    }

    @DELETE
    @Path("/customers/{id}")
    public Response deleteCustomer(@PathParam("id") UUID id) {
        settings.deleteCustomer(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/customer-codes")
    public List<CustomerCodeBillingResponse> customerCodes() {
        return settings.customerCodes().stream().map(CustomerCodeBillingResponse::from).toList();
    }

    @GET
    @Path("/customer-code-labels")
    public List<CustomerCodeLabelResponse> customerCodeLabels() {
        var shortNames = settings.customers().stream().filter(customer -> !customer.shortName().isBlank())
                .collect(java.util.stream.Collectors.toMap(com.timereport.invoicing.domain.BillingCustomer::id,
                        com.timereport.invoicing.domain.BillingCustomer::shortName));
        return settings.customerCodes().stream().filter(code -> code.customerId() != null && shortNames.containsKey(code.customerId()))
                .map(code -> CustomerCodeLabelResponse.builder().code(code.code())
                        .customerShortName(shortNames.get(code.customerId())).build()).toList();
    }

    @PUT
    @Path("/customer-codes/{code}")
    public CustomerCodeBillingResponse saveCustomerCode(@PathParam("code") String code,
                                                         @NotNull @Valid SaveCustomerCodeBillingRequest request) {
        var settingsForCode = CustomerCodeBilling.builder().code(code).customerId(request.customerId())
                .serviceDescription(request.serviceDescription()).hourlyRate(request.hourlyRate())
                .vatPercent(request.vatPercent()).build();
        return CustomerCodeBillingResponse.from(settings.saveCustomerCode(settingsForCode));
    }

    @DELETE
    @Path("/customer-codes/{code}")
    public Response removeCustomerCode(@PathParam("code") String code) {
        settings.removeCustomerCode(code);
        return Response.noContent().build();
    }

    private BillingCustomer customer(UUID id, SaveBillingCustomerRequest r) {
        return BillingCustomer.builder().id(id).customerNumber(r.customerNumber()).name(r.name()).shortName(r.shortName()).addressLine(r.addressLine())
                .postalCode(r.postalCode()).city(r.city()).country(r.country()).organizationNumber(r.organizationNumber())
                .vatNumber(r.vatNumber()).invoiceEmail(r.invoiceEmail()).customerReference(r.customerReference())
                .ourReference(r.ourReference()).paymentTermsDays(r.paymentTermsDays()).build();
    }
}
