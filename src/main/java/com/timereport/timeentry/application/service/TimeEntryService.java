package com.timereport.timeentry.application.service;

import com.timereport.timeentry.application.port.in.TimeEntries;
import com.timereport.timeentry.application.port.out.CustomerCodeRepository;
import com.timereport.timeentry.application.port.out.TimeEntryRepository;
import com.timereport.timeentry.application.port.out.WriteTransaction;
import com.timereport.timeentry.domain.TimeEntry;
import com.timereport.timeentry.domain.VersionedTimeEntry;
import com.timereport.timeentry.domain.WorkDay;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class TimeEntryService implements TimeEntries {
    private final TimeEntryRepository entries;
    private final CustomerCodeRepository codes;
    private final WriteTransaction transaction;

    public TimeEntryService(TimeEntryRepository entries, CustomerCodeRepository codes, WriteTransaction transaction) {
        this.entries = entries;
        this.codes = codes;
        this.transaction = transaction;
    }

    @Override
    public VersionedTimeEntry save(TimeEntry entry, long expectedVersion) {
        Objects.requireNonNull(entry, "Entry is required");
        Versions.validate(expectedVersion);
        return transaction.execute(() -> {
            VersionedTimeEntry current = entries.find(entry.id()).orElse(null);
            if (current != null && !current.deleted() && current.version() == expectedVersion + 1
                    && current.entry().equals(entry)) {
                return current;
            }
            if (current == null && expectedVersion != 0 || current != null
                    && (current.deleted() || current.version() != expectedVersion || expectedVersion == 0)) {
                throw new ConflictException("Time entry changed or was deleted; reload before saving");
            }
            var code = codes.find(entry.customerCode())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown customer code"));
            if (!code.active() && (current == null || !current.entry().customerCode().equals(entry.customerCode()))) {
                throw new IllegalArgumentException("Customer code is inactive");
            }
            var dayEntries = new ArrayList<>(entries.list(entry.date(), entry.date()).stream()
                    .map(VersionedTimeEntry::entry).filter(other -> !other.id().equals(entry.id())).toList());
            dayEntries.add(entry);
            try {
                WorkDay.builder().date(entry.date()).entries(dayEntries).build();
            } catch (IllegalArgumentException e) {
                throw new ConflictException(e.getMessage());
            }
            var saved = VersionedTimeEntry.builder().entry(entry).version(expectedVersion + 1).deleted(false).build();
            entries.save(saved, expectedVersion);
            return saved;
        });
    }

    @Override
    public VersionedTimeEntry get(UUID id) {
        return entries.find(id).filter(entry -> !entry.deleted())
                .orElseThrow(() -> new NotFoundException("Time entry not found"));
    }

    @Override
    public List<VersionedTimeEntry> list(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("An inclusive date range with from <= to is required");
        }
        return List.copyOf(entries.list(from, to));
    }

    @Override
    public void delete(UUID id, long expectedVersion) {
        Versions.validate(expectedVersion);
        if (expectedVersion == 0) {
            throw new IllegalArgumentException("Deletion requires a positive expected version");
        }
        transaction.execute(() -> {
            var current = entries.find(id).orElseThrow(() -> new NotFoundException("Time entry not found"));
            if (current.deleted() && current.version() == expectedVersion + 1) {
                return null;
            }
            if (current.deleted() || current.version() != expectedVersion) {
                throw new ConflictException("Time entry changed or was deleted; reload before deleting");
            }
            entries.save(current.toBuilder().version(expectedVersion + 1).deleted(true).build(), expectedVersion);
            return null;
        });
    }
}
