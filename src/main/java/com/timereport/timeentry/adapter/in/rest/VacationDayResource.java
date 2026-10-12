package com.timereport.timeentry.adapter.in.rest;

import com.timereport.timeentry.application.port.in.VacationDays;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Path("/api/vacation-days")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VacationDayResource {
    private final VacationDays vacations;

    public VacationDayResource(VacationDays vacations) {
        this.vacations = vacations;
    }

    @GET
    public List<VacationDayResponse> list(@QueryParam("from") String from, @QueryParam("to") String to) {
        if (from == null || to == null) throw new IllegalArgumentException("Both from and to are required (YYYY-MM-DD)");
        try {
            return vacations.list(LocalDate.parse(from), LocalDate.parse(to)).stream().map(VacationDayResponse::from).toList();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must use YYYY-MM-DD");
        }
    }

    @PUT
    @Path("/{date}")
    public void mark(@PathParam("date") String date) {
        vacations.set(parse(date), true);
    }

    @DELETE
    @Path("/{date}")
    public void unmark(@PathParam("date") String date) {
        vacations.set(parse(date), false);
    }

    private LocalDate parse(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date must use YYYY-MM-DD");
        }
    }
}
