package com.timereport.appearance.adapter.in.rest;

import com.timereport.appearance.domain.Appearance;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record AppearanceRequest(@NotNull Appearance.Theme theme) {}
