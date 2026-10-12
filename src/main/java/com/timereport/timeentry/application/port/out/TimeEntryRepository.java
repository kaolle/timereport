package com.timereport.timeentry.application.port.out;

import com.timereport.timeentry.domain.VersionedTimeEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimeEntryRepository {
    /** Includes tombstones so deleted IDs cannot be recreated. */
    Optional<VersionedTimeEntry> find(UUID id);
    List<VersionedTimeEntry> list(LocalDate from, LocalDate to);
    /** Must be called inside WriteTransaction. Zero means insert. */
    void save(VersionedTimeEntry entry, long expectedVersion);
}
