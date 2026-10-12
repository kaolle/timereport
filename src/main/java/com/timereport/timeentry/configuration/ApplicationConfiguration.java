package com.timereport.timeentry.configuration;

import com.timereport.timeentry.application.port.in.CustomerCodes;
import com.timereport.timeentry.application.port.in.TimeEntries;
import com.timereport.timeentry.application.port.in.VacationDays;
import com.timereport.timeentry.application.port.out.CustomerCodeRepository;
import com.timereport.timeentry.application.port.out.TimeEntryRepository;
import com.timereport.timeentry.application.port.out.VacationRepository;
import com.timereport.timeentry.application.port.out.WriteTransaction;
import com.timereport.timeentry.application.service.CustomerCodeService;
import com.timereport.timeentry.application.service.TimeEntryService;
import com.timereport.timeentry.application.service.VacationDayService;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class ApplicationConfiguration {
    @Produces
    @Singleton
    public TimeEntries timeEntries(TimeEntryRepository entries, CustomerCodeRepository codes, WriteTransaction transaction) {
        return new TimeEntryService(entries, codes, transaction);
    }

    @Produces
    @Singleton
    public CustomerCodes customerCodes(CustomerCodeRepository codes, WriteTransaction transaction) {
        return new CustomerCodeService(codes, transaction);
    }

    @Produces
    @Singleton
    public VacationDays vacationDays(VacationRepository vacations, TimeEntryRepository entries, WriteTransaction transaction) {
        return new VacationDayService(vacations, entries, transaction);
    }
}
