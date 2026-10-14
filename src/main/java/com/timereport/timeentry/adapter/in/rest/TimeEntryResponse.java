package com.timereport.timeentry.adapter.in.rest;

import com.timereport.timeentry.domain.VersionedTimeEntry;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Builder
public record TimeEntryResponse(UUID id, LocalDate date, LocalTime start, LocalTime end,
        int deductedMinutes, String customerCode, String comment, long workedMinutes, long version) {
    static TimeEntryResponse from(VersionedTimeEntry saved) {
        var entry = saved.entry();
        return TimeEntryResponse.builder().id(entry.id()).date(entry.date()).start(entry.start()).end(entry.end())
                .deductedMinutes(entry.deductedMinutes()).customerCode(entry.customerCode() == null ? null : entry.customerCode().value())
                .comment(entry.comment()).workedMinutes(entry.workedMinutes()).version(saved.version()).build();
    }
}
