package com.timereport.timeentry.adapter.in.rest;

import com.timereport.timeentry.application.port.in.CustomerCodes;
import com.timereport.timeentry.domain.CustomerCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/customer-codes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerCodeResource {
    private final CustomerCodes codes;

    public CustomerCodeResource(CustomerCodes codes) {
        this.codes = codes;
    }

    @PUT
    @Path("/{code}")
    public CustomerCodeResponse save(@PathParam("code") String code, @NotNull @Valid SaveCustomerCodeRequest request) {
        return CustomerCodeResponse.from(codes.save(new CustomerCode(code), request.active(), request.expectedVersion()));
    }

    @GET
    public List<CustomerCodeResponse> list() {
        return codes.list().stream().map(CustomerCodeResponse::from).toList();
    }
}
