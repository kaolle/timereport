package com.timereport.subscription.domain;

import lombok.Builder;

@Builder
public record MaintenanceResult(int checked, int failed, int remindersQueued, int terminated) {}
