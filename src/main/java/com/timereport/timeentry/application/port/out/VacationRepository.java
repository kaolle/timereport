package com.timereport.timeentry.application.port.out;

import com.timereport.timeentry.domain.VacationDay;

import java.time.LocalDate;
import java.util.List;

public interface VacationRepository {
    List<VacationDay> list(LocalDate from, LocalDate to);
    void set(VacationDay day, boolean vacation);
}
