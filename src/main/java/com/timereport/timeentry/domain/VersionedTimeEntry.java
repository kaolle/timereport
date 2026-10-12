package com.timereport.timeentry.domain;

import java.util.Objects;
import lombok.Builder;

@Builder(toBuilder = true)
public record VersionedTimeEntry(TimeEntry entry, long version, boolean deleted) {
    public VersionedTimeEntry {
        Objects.requireNonNull(entry, "Entry is required");
        if (version < 1) {
            throw new IllegalArgumentException("Version must be positive");
        }
    }
}
