package com.timereport.timeentry.application.port.in;

import com.timereport.timeentry.domain.VacationDay;

import java.time.LocalDate;
import java.util.List;

public interface VacationDays {
    List<VacationDay> list(LocalDate from, LocalDate to);
    void set(LocalDate date, boolean vacation);
}
