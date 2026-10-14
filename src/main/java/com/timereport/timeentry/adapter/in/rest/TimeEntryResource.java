package com.timereport.timeentry.adapter.in.rest;

import com.timereport.timeentry.application.port.in.TimeEntries;
import com.timereport.timeentry.domain.CustomerCode;
import com.timereport.timeentry.domain.TimeEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Path("/api/time-entries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TimeEntryResource {
    private final TimeEntries entries;

    public TimeEntryResource(TimeEntries entries) {
        this.entries = entries;
    }

    @PUT
    @Path("/{id}")
    public TimeEntryResponse save(@PathParam("id") UUID id, @NotNull @Valid SaveTimeEntryRequest request) {
        var entry = TimeEntry.builder().id(id).date(request.date()).start(request.start()).end(request.end())
                .deductedMinutes(request.deductedMinutes()).customerCode(request.customerCode() == null || request.customerCode().isBlank() ? null : new CustomerCode(request.customerCode()))
                .comment(request.comment()).build();
        return TimeEntryResponse.from(entries.save(entry, request.expectedVersion()));
    }

    @GET
    @Path("/{id}")
    public TimeEntryResponse get(@PathParam("id") UUID id) {
        return TimeEntryResponse.from(entries.get(id));
    }

    @GET
    public List<TimeEntryResponse> list(@QueryParam("from") String from, @QueryParam("to") String to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both from and to are required (YYYY-MM-DD)");
        }
        try {
            return entries.list(LocalDate.parse(from), LocalDate.parse(to)).stream().map(TimeEntryResponse::from).toList();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must use YYYY-MM-DD");
        }
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") UUID id, @QueryParam("expectedVersion") @NotNull @Min(1) Long expectedVersion) {
        entries.delete(id, expectedVersion);
    }
}
