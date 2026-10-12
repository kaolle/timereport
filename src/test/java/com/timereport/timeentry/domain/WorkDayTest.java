package com.timereport.timeentry.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkDayTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 8);

    @Test
    void emptyDayHasNoWorkedTimeAndSuggestsLunchDeduction() {
        WorkDay day = WorkDay.builder().date(DATE).entries(List.of()).build();
        assertEquals(0, day.workedMinutes());
        assertEquals(30, day.suggestedDeductedMinutes());
    }

    @Test
    void sumsAdjacentEntriesAcrossCustomerCodesWithoutSubtractingLunchTwice() {
        TimeEntry morning = entry("08:00", "12:00").toBuilder().deductedMinutes(30).build();
        TimeEntry afternoon = entry("12:00", "17:00").toBuilder()
                .customerCode(new CustomerCode("OTHER5678")).build();
        WorkDay day = new WorkDay(DATE, List.of(afternoon, morning));

        assertEquals(510, day.workedMinutes());
        assertEquals(0, day.suggestedDeductedMinutes());
    }

    @ParameterizedTest
    @CsvSource({"07:00,09:00", "08:00,10:00", "09:00,11:00", "09:00,12:00", "07:00,13:00"})
    void rejectsOverlapsRegardlessOfInputOrder(String start, String end) {
        TimeEntry first = entry("08:00", "12:00");
        TimeEntry second = entry(start, end);
        assertThrows(IllegalArgumentException.class, () -> new WorkDay(DATE, List.of(first, second)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkDay.builder().date(DATE).entries(List.of(second, first)).build());
    }

    @Test
    void rejectsDifferentDatesAndDuplicateIds() {
        TimeEntry first = entry("08:00", "12:00");
        TimeEntry wrongDate = first.toBuilder().date(DATE.plusDays(1)).build();
        TimeEntry duplicateId = first.toBuilder().start(LocalTime.of(13, 0)).end(LocalTime.of(17, 0)).build();
        assertThrows(IllegalArgumentException.class, () -> new WorkDay(DATE, List.of(wrongDate)));
        assertThrows(IllegalArgumentException.class, () -> new WorkDay(DATE, List.of(first, duplicateId)));
    }

    @Test
    void defensivelyCopiesEntriesAndExposesAnUnmodifiableList() {
        var entries = new ArrayList<>(List.of(entry("08:00", "12:00")));
        WorkDay day = WorkDay.builder().date(DATE).entries(entries).build();
        entries.clear();

        assertEquals(240, day.workedMinutes());
        assertThrows(UnsupportedOperationException.class, () -> day.entries().clear());
    }

    @Test
    void rejectsNullDateCollectionAndElements() {
        assertThrows(NullPointerException.class, () -> new WorkDay(null, List.of()));
        assertThrows(NullPointerException.class, () -> new WorkDay(DATE, null));
        assertThrows(NullPointerException.class, () -> new WorkDay(DATE, Arrays.asList((TimeEntry) null)));
    }

    private TimeEntry entry(String start, String end) {
        return TimeEntry.builder().id(UUID.randomUUID()).date(DATE)
                .start(LocalTime.parse(start)).end(LocalTime.parse(end))
                .deductedMinutes(0).customerCode(new CustomerCode("FAIR1234")).build();
    }
}
