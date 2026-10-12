package com.timereport.timeentry.application.service;

import com.timereport.timeentry.application.port.in.VacationDays;
import com.timereport.timeentry.application.port.out.VacationRepository;
import com.timereport.timeentry.application.port.out.TimeEntryRepository;
import com.timereport.timeentry.application.port.out.WriteTransaction;
import com.timereport.timeentry.domain.VacationDay;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public class VacationDayService implements VacationDays {
    private final VacationRepository vacations;
    private final TimeEntryRepository entries;
    private final WriteTransaction transaction;

    public VacationDayService(VacationRepository vacations, TimeEntryRepository entries, WriteTransaction transaction) {
        this.vacations = vacations;
        this.entries = entries;
        this.transaction = transaction;
    }

    @Override
    public List<VacationDay> list(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "Start date is required");
        Objects.requireNonNull(to, "End date is required");
        if (to.isBefore(from)) throw new IllegalArgumentException("End date must not be before start date");
        return List.copyOf(vacations.list(from, to));
    }

    @Override
    public void set(LocalDate date, boolean vacation) {
        var day = new VacationDay(date);
        transaction.execute(() -> {
            if (vacation) {
                entries.list(date, date).forEach(current -> entries.save(
                        current.toBuilder().version(current.version() + 1).deleted(true).build(), current.version()));
            }
            vacations.set(day, vacation);
            return null;
        });
    }
}
