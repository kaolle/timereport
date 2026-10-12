package com.timereport.timeentry.application.port.in;

import com.timereport.timeentry.domain.TimeEntry;
import com.timereport.timeentry.domain.VersionedTimeEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TimeEntries {
    VersionedTimeEntry save(TimeEntry entry, long expectedVersion);
    VersionedTimeEntry get(UUID id);
    List<VersionedTimeEntry> list(LocalDate from, LocalDate to);
    void delete(UUID id, long expectedVersion);
}
