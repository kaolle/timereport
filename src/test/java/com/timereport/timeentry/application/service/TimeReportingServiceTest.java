package com.timereport.timeentry.application.service;

import com.timereport.timeentry.application.port.out.CustomerCodeRepository;
import com.timereport.timeentry.application.port.out.TimeEntryRepository;
import com.timereport.timeentry.application.port.out.WriteTransaction;
import com.timereport.timeentry.domain.CustomerCode;
import com.timereport.timeentry.domain.RegisteredCustomerCode;
import com.timereport.timeentry.domain.TimeEntry;
import com.timereport.timeentry.domain.VersionedTimeEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class TimeReportingServiceTest {
    private final MemoryStore store = new MemoryStore();
    private final CustomerCodeService codes = new CustomerCodeService(store, store);
    private final TimeEntryService entries = new TimeEntryService(store, store, store);
    private final CustomerCode code = new CustomerCode("FAIR1234");
    private final LocalDate date = LocalDate.of(2026, 10, 10);

    @BeforeEach
    void registerCode() {
        codes.save(code, true, 0);
    }

    @Test
    void retriesDoNotCreateDuplicatesOrIncrementVersions() {
        var entry = entry();
        assertEquals(entries.save(entry, 0), entries.save(entry, 0));
        var edited = entry.toBuilder().comment("Updated").build();
        assertEquals(entries.save(edited, 1), entries.save(edited, 1));
        assertEquals(2, entries.get(entry.id()).version());
        assertEquals(1, entries.list(date, date).size());
    }

    @Test
    void staleOrReusedIdsCannotOverwriteCurrentData() {
        var entry = entry();
        entries.save(entry, 0);
        var edited = entry.toBuilder().comment("Updated").build();
        entries.save(edited, 1);
        assertThrows(ConflictException.class, () -> entries.save(entry, 0));
        assertThrows(ConflictException.class, () -> entries.save(entry.toBuilder().comment("Other edit").build(), 1));
        assertEquals(edited, entries.get(entry.id()).entry());
    }

    @Test
    void unknownCodesAndNewUseOfInactiveCodesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> entries.save(entry().toBuilder().customerCode(new CustomerCode("UNKNOWN")).build(), 0));
        codes.save(code, false, 1);
        assertThrows(IllegalArgumentException.class, () -> entries.save(entry(), 0));
        assertTrue(entries.list(date, date).isEmpty());
    }

    @Test
    void deactivationPreservesHistoryAndAllowsCorrectionsWithTheSameCode() {
        var entry = entry();
        entries.save(entry, 0);
        codes.save(code, false, 1);
        assertEquals(entry, entries.get(entry.id()).entry());
        assertEquals(2, entries.save(entry.toBuilder().comment("Correction").build(), 1).version());
    }

    @Test
    void overlapIsRejectedAcrossCodesButAdjacentEntriesAreAllowed() {
        entries.save(entry(), 0);
        var otherCode = new CustomerCode("OTHER");
        codes.save(otherCode, true, 0);
        assertThrows(ConflictException.class,
                () -> entries.save(entry().toBuilder().customerCode(otherCode).build(), 0));
        entries.save(entry().toBuilder().start(LocalTime.of(17, 0)).end(LocalTime.of(18, 0))
                .deductedMinutes(0).customerCode(otherCode).build(), 0);
        assertEquals(2, entries.list(date, date).size());
    }

    @Test
    void editingExcludesItselfButMovingToAnOccupiedDayConflicts() {
        var first = entry();
        var second = entry().toBuilder().date(date.plusDays(1)).build();
        entries.save(first, 0);
        entries.save(second, 0);
        assertThrows(ConflictException.class, () -> entries.save(first.toBuilder().date(second.date()).build(), 1));
        assertEquals(first, entries.get(first.id()).entry());
        assertEquals(2, entries.save(first.toBuilder().end(LocalTime.of(16, 0)).build(), 1).version());
    }

    @Test
    void deletionIsRetrySafeAndPreventsResurrection() {
        var entry = entry();
        entries.save(entry, 0);
        assertThrows(ConflictException.class, () -> entries.delete(entry.id(), 2));
        entries.delete(entry.id(), 1);
        entries.delete(entry.id(), 1);
        assertThrows(NotFoundException.class, () -> entries.get(entry.id()));
        assertTrue(entries.list(date, date).isEmpty());
        assertThrows(ConflictException.class, () -> entries.save(entry, 0));
        assertThrows(ConflictException.class, () -> entries.save(entry, 2));
        assertEquals(2, store.entries.get(entry.id()).version());
    }

    @Test
    void customerCodeUpdatesAreVersionedAndRetrySafe() {
        assertEquals(1, codes.save(code, true, 0).version());
        assertEquals(2, codes.save(code, false, 1).version());
        assertEquals(2, codes.save(code, false, 1).version());
        assertThrows(ConflictException.class, () -> codes.save(code, true, 1));
        assertThrows(ConflictException.class, () -> codes.save(new CustomerCode("MISSING"), true, 1));
    }

    @Test
    void validatesVersionsAndDateRangesAndReportsMissingEntries() {
        assertThrows(IllegalArgumentException.class, () -> entries.save(entry(), -1));
        assertThrows(IllegalArgumentException.class, () -> entries.save(entry(), Long.MAX_VALUE));
        assertThrows(ConflictException.class, () -> entries.save(entry(), 1));
        assertThrows(IllegalArgumentException.class, () -> entries.delete(UUID.randomUUID(), 0));
        assertThrows(NotFoundException.class, () -> entries.delete(UUID.randomUUID(), 1));
        assertThrows(NotFoundException.class, () -> entries.get(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> entries.list(null, date));
        assertThrows(IllegalArgumentException.class, () -> entries.list(date, date.minusDays(1)));
    }

    private TimeEntry entry() {
        return TimeEntry.builder().id(UUID.randomUUID()).date(date).start(LocalTime.of(8, 0))
                .end(LocalTime.of(17, 0)).customerCode(code).build();
    }

    /** Checks that service writes actually pass through the transaction port. */
    private static class MemoryStore implements TimeEntryRepository, CustomerCodeRepository, WriteTransaction {
        private final Map<UUID, VersionedTimeEntry> entries = new HashMap<>();
        private final Map<CustomerCode, RegisteredCustomerCode> codes = new HashMap<>();
        private boolean writing;

        @Override
        public <T> T execute(Supplier<T> work) {
            writing = true;
            try {
                return work.get();
            } finally {
                writing = false;
            }
        }

        @Override
        public Optional<VersionedTimeEntry> find(UUID id) {
            return Optional.ofNullable(entries.get(id));
        }

        @Override
        public List<VersionedTimeEntry> list(LocalDate from, LocalDate to) {
            return entries.values().stream().filter(e -> !e.deleted()
                    && !e.entry().date().isBefore(from) && !e.entry().date().isAfter(to)).toList();
        }

        @Override
        public void save(VersionedTimeEntry entry, long expectedVersion) {
            assertTrue(writing);
            entries.put(entry.entry().id(), entry);
        }

        @Override
        public Optional<RegisteredCustomerCode> find(CustomerCode code) {
            return Optional.ofNullable(codes.get(code));
        }

        @Override
        public List<RegisteredCustomerCode> list() {
            return List.copyOf(codes.values());
        }

        @Override
        public void save(RegisteredCustomerCode code, long expectedVersion) {
            assertTrue(writing);
            codes.put(code.code(), code);
        }
    }
}
