package com.timereport.timeentry.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeEntryTest {
    @Test
    void defaultsToThirtyMinutesForBothConstructionPaths() {
        TimeEntry built = validEntry().build();
        TimeEntry direct = new TimeEntry(built.id(), built.date(), built.start(), built.end(),
                null, built.customerCode(), null);

        assertEquals(30, built.deductedMinutes());
        assertEquals(510, built.workedMinutes());
        assertEquals(built, direct);
        assertEquals("", built.comment());
    }

    @ParameterizedTest
    @CsvSource({"0,540", "15,525", "30,510", "60,480", "540,0"})
    void subtractsExactlyTheChosenMinutes(int deduction, long expected) {
        assertEquals(expected, validEntry().deductedMinutes(deduction).build().workedMinutes());
    }

    @Test
    void preservesMinutePrecisionWithoutRoundingToQuarters() {
        TimeEntry entry = validEntry().start(LocalTime.of(8, 7)).end(LocalTime.of(9, 11))
                .deductedMinutes(0).build();
        assertEquals(64, entry.workedMinutes());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 541, Integer.MAX_VALUE})
    void rejectsInvalidDeductionsInConstructorAndBuilder(int deduction) {
        TimeEntry entry = validEntry().build();
        assertThrows(IllegalArgumentException.class,
                () -> entry.toBuilder().deductedMinutes(deduction).build());
        assertThrows(IllegalArgumentException.class,
                () -> new TimeEntry(entry.id(), entry.date(), entry.start(), entry.end(),
                        deduction, entry.customerCode(), entry.comment()));
    }

    @ParameterizedTest
    @CsvSource({"08:00,08:00", "17:00,08:00", "23:00,01:00",
            "08:00:01,17:00", "08:00,17:00:01", "08:00:00.000000001,17:00"})
    void rejectsReversedOvernightAndSubMinuteIntervals(String start, String end) {
        assertThrows(IllegalArgumentException.class,
                () -> validEntry().start(LocalTime.parse(start)).end(LocalTime.parse(end)).build());
    }

    @Test
    void shortIntervalsRequireAnExplicitSmallerDeduction() {
        assertThrows(IllegalArgumentException.class,
                () -> validEntry().end(LocalTime.of(8, 15)).build());
        assertEquals(15, validEntry().end(LocalTime.of(8, 15)).deductedMinutes(0).build().workedMinutes());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(NullPointerException.class, () -> validEntry().id(null).build());
        assertThrows(NullPointerException.class, () -> validEntry().date(null).build());
        assertThrows(NullPointerException.class, () -> validEntry().start(null).build());
        assertThrows(NullPointerException.class, () -> validEntry().end(null).build());
        assertThrows(NullPointerException.class, () -> validEntry().customerCode(null).build());
    }

    @Test
    void editingWithBuilderPreservesOriginalAndIdentity() {
        TimeEntry original = validEntry().comment("Implementation").build();
        TimeEntry edited = original.toBuilder().deductedMinutes(15).comment("Review").build();

        assertEquals(original.id(), edited.id());
        assertEquals(510, original.workedMinutes());
        assertEquals("Implementation", original.comment());
        assertEquals(525, edited.workedMinutes());
        assertEquals("Review", edited.comment());
    }

    private TimeEntry.TimeEntryBuilder validEntry() {
        return TimeEntry.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.of(2026, 10, 8))
                .start(LocalTime.of(8, 0))
                .end(LocalTime.of(17, 0))
                .customerCode(CustomerCode.builder().value("FAIR1234").build());
    }
}
